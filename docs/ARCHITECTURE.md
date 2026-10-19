# RAF — Architecture backend

Quiz payant en ligne. Les joueurs achètent des tickets avec des crédits, jouent une
partie chronométrée, et les meilleurs scores remportent des récompenses.

**Stack** : Spring Boot 4.1, Java 21, MySQL 8, Flyway, JWT (JJWT), Resend (email),
Web Push VAPID, ImageKit (médias).

---

## 1. Décisions structurantes

| Sujet | Décision |
|---|---|
| Déroulé du quiz | **Asynchrone** : le quiz est ouvert sur une fenêtre, chaque joueur lance sa partie quand il veut |
| Durée d'une partie | Définie par quiz (défaut 120 s), chrono **faisant autorité côté serveur** |
| Temps par question | Aucun. Pas de skip : le joueur reste sur la question tant qu'il n'a pas répondu |
| Barème | Points fixes par bonne réponse, pas de malus |
| Anti-triche | **Révélation différée** : questions envoyées sans corrigé, partie hors-ligne, score recalculé au submit, corrigé détaillé seulement après la clôture du quiz |
| Tickets | 1 ticket = 1 partie. Plafond **par joueur** et plafond **total** par quiz (null = illimité) |
| Score retenu | Le **meilleur** score du joueur sur le quiz |
| Départage | À score égal, le joueur ayant **démarré sa partie en premier** est prioritaire |
| Démarrage | Seuil de joueurs atteint → démarrage `start_delay_minutes` plus tard |
| Seuil non atteint | Attente illimitée + action admin « Annuler et rembourser » |
| Clôture | 1er joueur dépassant `min_score_threshold` → compte à rebours `closing_countdown_minutes`. Inscriptions ouvertes pendant |
| Récompenses | **Montants fixes** par tranche de rang, définis par l'admin |
| Sujet du quiz | Choisi par l'admin, ou voté par les **détenteurs de ticket** (1 vote/compte, veto admin) |
| Crédits | **1 crédit = 5 HTG**. Prix du ticket défini par quiz |
| Portefeuille | **2 soldes séparés** : crédits (non retirables) / gains (retirables) |
| Paiement | MonCash, NatCash, recharge manuelle admin |
| Comptes | Email (connexion) + téléphone vérifié par OTP (retrait, anti-doublon) |

---

## 2. Découpage en modules

```
com.raikiservice.raf
├── common/          config, exceptions, sécurité, utilitaires, audit
├── auth/            User, Role, RefreshToken, OTP, vérification email
├── wallet/          Wallet, transactions, recharges, retraits
├── payment/         Connecteurs MonCash / NatCash / manuel + webhooks
├── question/        Catégories, banque de questions, import CSV
├── quiz/            Quiz, sujets, votes, récompenses, tickets
├── game/            Sessions de jeu, réponses, scoring, classement
├── notification/    In-app, email (Resend), web push (VAPID)
├── media/           Intégration ImageKit
└── admin/           Back-office, statistiques, journal d'audit
```

---

## 3. Schéma de base de données

### 3.1 Authentification

**users**

| Colonne | Type | Notes |
|---|---|---|
| id | BIGINT PK | |
| email | VARCHAR(180) | UNIQUE, identifiant de connexion |
| phone | VARCHAR(20) | UNIQUE, format E.164 (+509…) |
| password_hash | VARCHAR(100) | BCrypt |
| first_name / last_name | VARCHAR(80) | |
| avatar_media_id | BIGINT FK → media_files | nullable |
| status | ENUM | ACTIVE, SUSPENDED, BANNED |
| email_verified_at | DATETIME | nullable |
| phone_verified_at | DATETIME | nullable — **requis pour retirer** |
| created_at / updated_at / last_login_at | DATETIME | |

**roles** (`PLAYER`, `MODERATOR`, `ADMIN`, `SUPER_ADMIN`) et **user_roles** (n-n).

**refresh_tokens** — rotation avec détection de réutilisation

`id, user_id, token_hash, family_id, expires_at, revoked_at, replaced_by_id, user_agent, ip, created_at`

→ si un token déjà consommé est rejoué, **toute la famille est révoquée** (vol de token).

**otp_codes**

`id, user_id, purpose (PHONE_VERIFY | WITHDRAWAL | PASSWORD_RESET), code_hash, expires_at, consumed_at, attempts`

**verification_tokens**

`id, user_id, type (EMAIL_VERIFY | PASSWORD_RESET), token_hash, expires_at, consumed_at`

### 3.2 Portefeuille

**wallets**

`id, user_id UNIQUE, credit_balance BIGINT, winnings_balance BIGINT, version INT (verrou optimiste), created_at, updated_at`

→ soldes stockés **en crédits** (entiers, jamais de flottant). Conversion en HTG (×5) à l'affichage et au retrait.

**wallet_transactions** — journal immuable, jamais modifié après insertion

| Colonne | Notes |
|---|---|
| id, wallet_id | |
| type | TOPUP, TICKET_PURCHASE, PRIZE, WITHDRAWAL, REFUND, ADJUSTMENT |
| balance_type | CREDIT ou WINNINGS |
| amount | BIGINT signé (+ crédit, − débit) |
| balance_after | BIGINT, solde après opération |
| reference_type / reference_id | polymorphe : ticket, retrait, recharge, classement |
| idempotency_key | UNIQUE nullable — **bloque le double débit** |
| description, created_at | |

**topup_requests**

`id, user_id, provider (MONCASH | NATCASH | MANUAL), amount_htg, credits, status (PENDING | CONFIRMED | FAILED | EXPIRED), provider_ref UNIQUE, raw_payload JSON, confirmed_by_admin_id, created_at, confirmed_at`

→ **aucun crédit n'est versé avant le callback confirmé**.

**withdrawal_requests**

`id, user_id, amount_credits, amount_htg, fee_htg, provider, destination_number, status (PENDING | APPROVED | PAID | REJECTED), otp_verified_at, reviewed_by_admin_id, reviewed_at, rejection_reason, created_at`

→ les gains sont **gelés** (débités du solde) dès la demande, et re-crédités en cas de rejet.

### 3.3 Banque de questions

**categories** — `id, name, slug UNIQUE, parent_id, media_id, active`

**questions**

`id, category_id, text TEXT, type (SINGLE_CHOICE | TRUE_FALSE), difficulty (EASY | MEDIUM | HARD), media_id, explanation, status (DRAFT | PUBLISHED | ARCHIVED), lang CHAR(2) DEFAULT 'fr', times_served INT, times_correct INT, created_by, created_at`

→ `times_served` / `times_correct` permettent de recalibrer la difficulté réelle.

**question_choices** — `id, question_id, text, is_correct BOOLEAN, position, media_id`

### 3.4 Quiz

**quizzes**

| Colonne | Notes |
|---|---|
| id, title, slug UNIQUE, description | |
| banner_media_id | |
| theme_label | thème général annoncé avant le vote |
| status | DRAFT, VOTE_OPEN, REGISTRATION, SCHEDULED, RUNNING, CLOSING, COMPUTING, FINISHED, CANCELLED |
| topic_mode | ADMIN ou VOTE |
| selected_category_id | sujet final retenu |
| ticket_price_credits | INT |
| min_players | INT — seuil de démarrage |
| max_tickets_total | INT nullable |
| max_tickets_per_player | INT nullable |
| start_delay_minutes | délai entre seuil atteint et démarrage |
| threshold_reached_at / scheduled_start_at / started_at | DATETIME |
| game_duration_seconds | défaut 120 |
| questions_per_game | INT |
| points_per_correct | INT |
| min_score_threshold | déclencheur du compte à rebours final |
| closing_countdown_minutes | INT |
| closing_triggered_at / ends_at / finished_at | DATETIME |
| vote_opens_at / vote_closes_at | DATETIME |
| created_by, created_at, updated_at | |

**quiz_topics** — candidats au vote : `id, quiz_id, category_id, label, media_id, vote_count`

**topic_votes** — `id, quiz_id, quiz_topic_id, user_id, created_at` — UNIQUE(quiz_id, user_id)

**quiz_rewards** — tranches de rang : `id, quiz_id, rank_from, rank_to, amount_credits, label`

> Exemple : (1,1,10000) · (2,2,5000) · (3,3,2500) · (4,10,500) · (11,50,100)

**tickets**

`id, quiz_id, user_id, code UNIQUE, price_paid_credits, status (ACTIVE | USED | REFUNDED), transaction_id, purchased_at, used_at`

**quiz_question_pool** — pool figé au démarrage : `id, quiz_id, question_id` — UNIQUE(quiz_id, question_id)

### 3.5 Jeu

**game_sessions**

| Colonne | Notes |
|---|---|
| id, quiz_id, user_id | |
| ticket_id | UNIQUE — 1 ticket = 1 session |
| status | IN_PROGRESS, SUBMITTED, EXPIRED, INVALIDATED |
| started_at | **fait autorité, sert au départage** |
| server_deadline_at | started_at + durée + marge réseau |
| submitted_at, client_duration_ms | |
| score, correct_count, answered_count | recalculés côté serveur |
| ip, user_agent | détection d'abus |

**game_session_questions** — le tirage figé du joueur

`id, session_id, question_id, position, correct_choice_id`

→ snapshot : une modification ultérieure de la question ne change pas le score déjà calculé.

**game_answers** — `id, session_id, question_id, choice_id, is_correct, elapsed_ms, points_awarded`

**quiz_rankings** — classement figé à la clôture

`id, quiz_id, user_id, best_session_id, score, first_started_at, rank, reward_credits, paid_at, transaction_id`

→ tri : `score DESC, first_started_at ASC`

### 3.6 Notifications & médias

**notifications** — `id, user_id, type, title, body, data JSON, read_at, created_at`

**notification_preferences** — `user_id, type, channel (IN_APP | EMAIL | PUSH), enabled`

**push_subscriptions** — `id, user_id, endpoint TEXT, p256dh, auth_key, user_agent, created_at, last_used_at`

**media_files** — `id, imagekit_file_id, url, thumbnail_url, folder, mime_type, size_bytes, uploaded_by, created_at`

### 3.7 Transverse

**audit_logs** — `id, actor_user_id, action, entity_type, entity_id, before JSON, after JSON, ip, created_at`

→ trace obligatoire sur : création/modification de quiz, modification de récompense, validation de retrait, ajustement de solde, bannissement.

**app_settings** — `key UNIQUE, value, description` (paramètres modifiables sans redéploiement).

---

## 4. Déroulé d'une partie (anti-triche)

**Modèle retenu : révélation différée.** Le corrigé ne quitte jamais le serveur
pendant la partie, et la partie se joue entièrement hors-ligne.

```
POST /api/game/start           (ticket valide requis)

  serveur : tire N questions du pool, fige le tirage, pose started_at + deadline
  → { sessionId, durationSeconds,
      questions: [ { id, texte, choix: [ { id, texte } ] } ] }
                                            ↑ aucun indicateur de bonne réponse

  Le front joue les 120 s en LOCAL — zéro latence, zéro appel réseau.
  À chaque clic il affiche « réponse enregistrée », sans ✓ ni ✗.

POST /api/game/submit
  → { sessionId, reponses: [ { questionId, choixId, elapsedMs } ] }
  ← { score, correctCount, answeredCount }     ← PAS le détail des réponses

  Validations serveur — toute violation ⇒ session INVALIDATED :
    1. session IN_PROGRESS et appartenant à l'appelant
    2. maintenant ≤ server_deadline_at (+ marge réseau)
    3. chaque questionId appartient bien au tirage de la session
    4. pas de doublon de réponse ; elapsedMs croissants et ≤ durée
    5. score recalculé depuis correct_choice_id — la valeur du client est ignorée

GET /api/game/{sessionId}/review
  Corrigé détaillé question par question.
  Accessible UNIQUEMENT si quiz.status = FINISHED (403 sinon).
```

### Pourquoi pas de vérification côté client

> Si le client peut vérifier une réponse sans réseau, il peut vérifier **les 4**
> sans réseau.

C'est une limite mathématique, pas un défaut d'implémentation. Chiffrer le corrigé
impose de livrer la clé au client. Le hacher ne protège rien : l'espace des entrées
fait 4 valeurs, donc un attaquant teste tous les candidats avant de cliquer. Aucun
schéma cryptographique ne contourne ça — seul un aller-retour réseau le peut.

### Fuite des questions entre joueurs (spécifique à l'asynchrone)

Un joueur qui a fini connaît ses questions et peut les transmettre aux suivants.
Trois barrières :

1. **Tirage aléatoire par joueur** dans un pool nettement plus large que
   `questions_per_game` (viser un pool ≥ 10× le nombre de questions servies).
2. **Corrigé verrouillé jusqu'à la clôture du quiz** — le tricheur peut partager les
   énoncés, pas les bonnes réponses.
3. `times_served` / `times_correct` : un taux de réussite anormal sur une question
   déjà beaucoup servie signale une fuite.

**À durcir ensuite** : limitation de débit sur `/game/start`, détection des temps de
réaction impossibles (< 400 ms répétés), corrélation IP / appareil entre comptes.

---

## 5. Cycle de vie du quiz

```
DRAFT               configuration : récompenses, sujet(s), paramètres de jeu
  └─ publication ─→ REGISTRATION

REGISTRATION        vente des tickets ; attente illimitée du seuil
  │                 ┌───────────────────────────────────────────────┐
  │                 │ VOTE DU SUJET (phase superposée, mode VOTE)   │
  │                 │ ouverte de vote_opens_at à vote_closes_at     │
  │                 │ réservée aux détenteurs de ticket             │
  │                 │ clôture = action admin (suivre le vote        │
  │                 │ ou imposer un sujet parmi les candidats)      │
  │                 └───────────────────────────────────────────────┘
  ├─ min_players atteint ─→ SCHEDULED    (démarrage dans start_delay_minutes)
  └─ action admin        ─→ CANCELLED    (remboursement intégral)

SCHEDULED ─→ RUNNING                     fige le vivier, ouvre le jeu
  └─ un joueur dépasse min_score_threshold
       └─→ CLOSING                       compte à rebours, inscriptions ouvertes
             └─ ends_at ─→ COMPUTING ─→ FINISHED
                            classement figé, récompenses versées en solde GAINS
```

> **Pourquoi le vote n'est pas une étape du cycle.** Le vote est réservé aux
> détenteurs de ticket ; placer une phase `VOTE_OPEN` *avant* l'ouverture des
> ventes rendrait donc le vote impossible — personne n'aurait de ticket. Les
> ventes ouvrent à la publication et le vote se déroule en parallèle. C'est aussi
> pour ça que `theme_label` existe : le joueur achète sur la foi du thème général
> annoncé, ce qui lui donne le droit de peser sur le sujet précis.

---

## 6. Règles financières

- **Achat de ticket** : débit atomique du solde crédits, verrou pessimiste sur le
  portefeuille + clé d'idempotence. Les deux plafonds (par joueur, total quiz) sont
  vérifiés dans la même transaction.
- **Annulation de quiz** : remboursement de tous les tickets en solde crédits.
- **Versement des gains** : uniquement en solde GAINS (retirable).
- Les rangs sans joueur ne sont pas payés.
- **Alerte admin** : à la configuration, afficher le seuil de rentabilité
  `Σ récompenses ÷ prix du ticket` et avertir si `min_players` est en dessous.

---

## 7. Etat d'avancement

### Lot 1 - Fondations (livre)

Java 21, Flyway (`spring.jpa.hibernate.ddl-auto=validate` : le schema n'est jamais
genere par Hibernate), springdoc/Swagger sur `/swagger-ui.html`, gestion d'erreurs
centralisee avec un catalogue de codes stables pour le frontend, journal d'audit,
table `app_settings`.

> Pieges Boot 4 rencontres, utiles pour la suite : les auto-configurations sont
> modulaires (`spring-boot-starter-flyway` et `spring-boot-starter-jackson` sont
> requis, les bibliotheques seules ne declenchent rien), Jackson est passe en
> version 3 (`tools.jackson.*`), et depuis le JDK 24 Lombok doit etre declare
> explicitement dans `annotationProcessorPaths`.

### Lot 2 - Authentification (livre)

| Methode | Endpoint | Acces |
|---|---|---|
| POST | `/api/auth/register` | public |
| POST | `/api/auth/login` | public |
| POST | `/api/auth/refresh` | public |
| POST | `/api/auth/logout` | public |
| POST | `/api/auth/email/verify` | public |
| POST | `/api/auth/email/resend` | public |
| POST | `/api/auth/password/forgot` | public |
| POST | `/api/auth/password/reset` | public |
| POST | `/api/auth/password/change` | authentifie |
| POST | `/api/auth/phone/send-otp` | authentifie |
| POST | `/api/auth/phone/verify` | authentifie |
| GET | `/api/me` | authentifie |
| GET | `/api/me/sessions` | authentifie |
| DELETE | `/api/me/sessions/{id}` | authentifie |
| DELETE | `/api/me/sessions` | authentifie |

**Regle transactionnelle a retenir pour les lots suivants.** Toute ecriture qui doit
survivre a l'exception levee juste apres (compteur d'essais OTP, revocation d'une
famille de jetons, trace d'audit) doit se faire dans une transaction independante
(`REQUIRES_NEW`), et la capture d'erreur doit rester *en dehors* de la frontiere
transactionnelle. Un `try/catch` interne n'empeche pas le marquage rollback-only et
le commit echoue quand meme.

### Lot 3 - Portefeuille (livre)

| Methode | Endpoint | Acces |
|---|---|---|
| GET | `/api/wallet` | joueur |
| GET | `/api/wallet/transactions` | joueur |
| GET | `/api/wallet/topups` | joueur |
| POST | `/api/wallet/withdrawals/send-otp` | joueur |
| POST | `/api/wallet/withdrawals` | joueur |
| GET | `/api/wallet/withdrawals` | joueur |
| POST | `/api/admin/wallet/topups/manual` | admin |
| GET | `/api/admin/wallet/topups` | admin |
| GET | `/api/admin/wallet/withdrawals` | admin |
| POST | `/api/admin/wallet/withdrawals/{id}/approve` | admin |
| POST | `/api/admin/wallet/withdrawals/{id}/reject` | admin |
| POST | `/api/admin/wallet/withdrawals/{id}/paid` | admin |
| POST | `/api/admin/wallet/users/{id}/adjust` | admin |
| GET | `/api/admin/wallet/users/{id}` | admin |
| GET | `/api/admin/wallet/users/{id}/transactions` | admin |

`WalletService` est le **seul** point d'ecriture des soldes. Les lots suivants
(achat de ticket, versement des gains) doivent passer par `credit` / `debit`, qui
sont en `MANDATORY` : un mouvement d'argent n'existe que dans l'operation metier
qui le justifie.

Le premier administrateur est cree au demarrage par `AdminBootstrap` a partir des
variables `BOOTSTRAP_ADMIN_*`, puis ces variables doivent etre vidées.

**Verrouillage : trois regles apprises en testant la concurrence.** Elles valent
pour toute future table soumise a des ecritures concurrentes (tickets, classements).

1. *La lecture verrouillee doit etre la premiere lecture de l'entite dans la
   transaction.* Si une lecture non verrouillee l'a precedee, Hibernate sert
   l'entite depuis son cache de premier niveau sans relire la ligne : le verrou est
   pris en base, mais la valeur utilisee est perimee. Deux debits concurrents
   partent alors du meme solde.
2. *Ne jamais poser de verrou sur une ligne absente.* Sous InnoDB, un
   `SELECT ... FOR UPDATE` qui ne trouve rien pose un verrou d'intervalle qui
   bloque l'insertion dans cette plage — y compris celle que l'on s'apprete a
   faire depuis une transaction imbriquee. Resultat : auto-interblocage puis
   epuisement du pool. Tester l'existence par un `COUNT` d'abord.
3. *Un appel interne (`this.methode()`) ne passe pas par le proxy Spring*, donc
   `@Transactional` y est silencieusement ignore. Tout ce qui exige une propagation
   particuliere vit dans un bean distinct (`WalletProvisioner`, `AuditWriter`,
   `RefreshTokenRevoker`, `OtpAttemptRecorder`, `QuestionRowImporter`).

### Lot 4 - Medias et banque de questions (livre)

| Methode | Endpoint | Acces |
|---|---|---|
| GET | `/api/categories` | joueur |
| GET | `/api/categories/{id}` | joueur |
| GET | `/api/categories/all` | moderateur |
| POST / PUT | `/api/categories` , `/api/categories/{id}` | moderateur |
| DELETE | `/api/categories/{id}` | admin |
| POST / PUT | `/api/admin/questions` , `/{id}` | moderateur |
| GET | `/api/admin/questions` , `/{id}` , `/search` | moderateur |
| POST | `/api/admin/questions/{id}/status` | moderateur |
| DELETE | `/api/admin/questions/{id}` | moderateur |
| POST | `/api/admin/questions/import` | moderateur |
| GET | `/api/admin/questions/pool-status` | moderateur |
| GET | `/api/media/auth-params` | authentifie |
| POST | `/api/media` , `/api/media/upload` | authentifie / moderateur |
| DELETE | `/api/media/{id}` | moderateur |

**La banque de questions est fermee aux joueurs.** Le role MODERATOR existe pour
que le travail editorial n'exige pas les droits financiers.

**Anti-doublon.** `questions.text_hash` porte l'unicite par categorie : empreinte
de l'enonce normalise (sans casse, accents ni ponctuation). C'est ce qui permet de
reimporter le meme CSV sans rien dupliquer — verifie par le test 11.

**Format CSV** (separateur `;` ou `,` detecte automatiquement) :

```
question;reponse_a;reponse_b;reponse_c;reponse_d;bonne_reponse;difficulte;explication
```

`bonne_reponse` = A/B/C/D, `difficulte` = FACILE/MOYEN/DIFFICILE (vide = MOYEN),
`reponse_c` et `reponse_d` vides pour une question vrai/faux. Une ligne fautive est
rejetee et motivee dans le rapport, sans interrompre l'import.

**ImageKit.** Deux voies : le frontend televerse directement avec une signature
courte obtenue sur `/api/media/auth-params` (la cle privee ne sort jamais du
serveur), ou le backend televerse lui-meme pour les flux administratifs. Sans cles
configurees, ces endpoints renvoient 503 avec un message explicite.

**Regle de mapping a retenir.** `open-in-view=false` : la conversion entite -> DTO
doit se faire **dans** la transaction. Les services renvoient donc des DTO, jamais
des entites, sauf pour un usage interne explicitement documente. Un controleur qui
mappe une entite apres la fermeture de la session leve une
`LazyInitializationException` des qu'une relation paresseuse est non nulle — une
panne qui n'apparait pas tant que les donnees de test sont vides.

### Lot 5 - Quiz, vote, recompenses et tickets (livre)

| Methode | Endpoint | Acces |
|---|---|---|
| GET | `/api/quizzes` , `/api/quizzes/{id}` | joueur |
| POST | `/api/quizzes/{id}/tickets` | joueur |
| GET | `/api/quizzes/{id}/tickets` | joueur |
| POST | `/api/quizzes/{id}/vote/{topicId}` | joueur avec ticket |
| POST / PUT | `/api/admin/quizzes` , `/{id}` | admin |
| POST | `/api/admin/quizzes/{id}/rewards` | admin |
| GET | `/api/admin/quizzes/{id}/economics` | admin |
| POST | `/api/admin/quizzes/{id}/topics` | admin |
| POST | `/api/admin/quizzes/{id}/publish` | admin |
| POST | `/api/admin/quizzes/{id}/close-vote` | admin |
| POST | `/api/admin/quizzes/{id}/start` | admin |
| POST | `/api/admin/quizzes/{id}/cancel` | admin |
| GET / POST | `/api/admin/users` , `/{id}/roles` , `/{id}/status` | super-admin |

**Verrous a la publication.** Un quiz ne part en vente que si : au moins une
tranche de recompense est definie, les tranches couvrent les rangs sans trou ni
chevauchement a partir de 1, le sujet est exploitable, et **chaque** categorie
candidate a un vivier suffisant. Apres la publication, plus aucun parametre n'est
modifiable : des tickets ont ete vendus sur la foi de ces regles.

**Le vivier est fige au demarrage.** `quiz_question_pool` recoit une copie des
questions publiees de la categorie retenue. Une question publiee ou archivee
ensuite n'entre ni ne sort du quiz en cours : le premier et le dernier joueur
tirent dans le meme ensemble.

**Point d'equilibre.** `GET /{id}/economics` renvoie la cagnotte totale, le nombre
de joueurs necessaire pour la couvrir et la marge au seuil minimum. Les montants
etant fixes, `min_players` est la seule protection financiere du quiz : l'endpoint
existe pour que le deficit soit visible avant publication, pas apres.

**Idempotence de l'achat.** Le frontend genere une cle a l'ouverture de l'ecran
d'achat et la renvoie a chaque tentative. Trois envois avec la meme cle donnent un
seul ticket et un seul debit — verifie en conditions reelles.

**Gestion des roles reservee au SUPER_ADMIN.** Donner le role ADMIN ouvre l'acces
a la validation des retraits, donc a l'argent : un ADMIN ne doit pas pouvoir se
promouvoir lui-meme ni promouvoir un complice. Un changement de role revoque
toutes les sessions du compte vise, car les roles sont portes par le jeton d'acces
et resteraient valables jusqu'a son expiration.

**Ordonnanceur.** `QuizScheduler` (toutes les 30 s) fait passer SCHEDULED ->
RUNNING a l'heure prevue et CLOSING -> COMPUTING a la cloture. Il suppose une
instance unique ; les transitions re-verifient l'etat sous verrou, donc un double
traitement est sans effet, mais ShedLock sera necessaire avant de scaler.

### Lot 6 - Parties, classement et gains (livre)

| Methode | Endpoint | Acces |
|---|---|---|
| POST | `/api/game/start` | joueur avec ticket |
| POST | `/api/game/submit` | joueur |
| GET | `/api/game/sessions?quizId=` | joueur |
| GET | `/api/game/{sessionId}/review` | joueur, quiz FINISHED |
| GET | `/api/quizzes/{id}/leaderboard` | joueur, quiz FINISHED |
| GET | `/api/admin/quizzes/{id}/sessions?flagged=` | admin |
| GET | `/api/admin/game/sessions/{id}/review` | admin |
| POST | `/api/admin/game/sessions/{id}/invalidate` | admin |
| POST | `/api/admin/quizzes/{id}/finalize` | admin |

Migration `V6__game.sql` : `game_sessions`, `game_session_questions`,
`game_answers`, `quiz_rankings` (la colonne de rang s'appelle `rank_position` :
`rank` est reserve depuis MySQL 8.0.2), et trois parametres `game.*`.

**Reprise plutot que double consommation.** `/start` renvoie la partie en cours
si le joueur en a une (200, `resumed=true`, meme tirage, meme ordre des
propositions, chrono restant calcule par le serveur) ; sinon il consomme le ticket
le plus ancien (201). Deux clics simultanes visent donc le meme ticket : le second
attend le verrou du premier et reprend sa partie — verifie en conditions reelles.

**Le submit n'echoue pas, il invalide.** Toute incoherence (reponse n ne portant
pas sur la question n du tirage, choix etranger a la question, temps decroissants
ou superieurs a la duree, temps declare superieur au temps ecoule cote serveur)
passe la partie en INVALIDATED dans une transaction qui commit. Lever une
exception annulerait l'invalidation et laisserait le joueur resoumettre jusqu'a
passer. Le submit est idempotent : un rejeu renvoie le resultat deja calcule.

**Signalement, pas sanction.** Des reponses enchainees en moins de
`game.min_answer_ms` (defaut 400 ms), au-dela de `game.fast_answer_flag_count`
occurrences, marquent la partie `flagged` pour revue. L'admin peut ensuite
l'invalider tant que le classement n'est pas fige.

**Cloture.** Le premier score strictement superieur a `min_score_threshold`
declenche `QuizService.triggerClosing` (etat et seuil re-verifies sous verrou).
Apres `ends_at`, `QuizScheduler` passe le quiz en COMPUTING ; `GameScheduler`
expire les parties echues puis, quand plus aucune n'est IN_PROGRESS, fige le
classement (score DESC, debut de la partie ASC, id ASC) et verse les gains en
solde GAINS, le tout dans une transaction sous le verrou du quiz.

**Regles de verrouillage ajoutees.**

1. *Ordre des verrous* : quiz (partage) puis ticket au demarrage ; partie puis
   quiz au submit et a l'invalidation. Le verrou partage au demarrage fait
   attendre la cloture (exclusive) : aucune partie ne demarre sur un quiz clos.
2. *Le submit ne charge jamais le quiz* : duree et bareme sont copies dans la
   partie, et le seuil est lu par projection scalaire. Ainsi le verrou de
   `triggerClosing` reste la premiere lecture du quiz (regle 1 du lot 3).
3. *Sous REPEATABLE READ, une lecture simple ne voit pas ce qu'une transaction
   concurrente vient de commiter.* Pour retrouver la partie creee par un clic
   concurrent, il faut une lecture verrouillee (`findByTicketIdForShare`). Pour la
   meme raison, le classement attend qu'aucune partie ne soit IN_PROGRESS plutot
   que de les expirer lui-meme : une soumission en vol lui serait invisible.
4. *Pas de promotion de verrou sur une ligne partagee.* Inserer le tirage pose un
   verrou partage sur les questions (cle etrangere) ; incrementer ensuite
   `times_served` dans la meme transaction exigerait un verrou exclusif, et deux
   parties partageant une question s'interbloqueraient. Les compteurs sont donc
   ecrits apres le commit, dans une transaction separee (`QuestionStatsRecorder`).

### Lot 7 - Paiements (a venir)

**Exigence : les depots et retraits manuels restent possibles depuis le
back-office, en plus des connecteurs MonCash / NatCash** (decision du
2026-10-19). Etat actuel et ce qu'il reste a ajouter :

| Operation | Existe | A ajouter |
|---|---|---|
| Depot manuel | `POST /api/admin/wallet/topups/manual` (provider `MANUAL`, reference idempotente) | rien d'obligatoire |
| Retrait demande par le joueur, paye a la main | demande joueur + `approve` / `paid` / `reject` admin | accepter `MANUAL` sans numero de reception (remise en especes) |
| Retrait saisi par l'admin pour un joueur (guichet) | non — seul l'`ADJUSTMENT` permet de debiter, ce qui fausse la comptabilite | endpoint admin dedie : debit du solde GAINS en type `WITHDRAWAL`, reference idempotente, trace d'audit, **OTP du joueur exige** |

**Le retrait au guichet exige le code OTP recu par le joueur.** Sans lui, un
administrateur — ou un compte administrateur compromis — pourrait vider les gains
de n'importe quel joueur en son absence. Le joueur present communique le code
recu sur son telephone ; l'admin le saisit avec la demande.

Toutes ces operations passent par `WalletService` et laissent une trace
d'audit, comme les autres mouvements d'argent.

---

## 8. Points ouverts

- Cadre légal haïtien du jeu payant : licence, âge minimum 18+, jeu responsable
  (plafond de dépense, auto-exclusion), fiscalité des gains.
- `RateLimiter` compte en mémoire : sur plusieurs instances, le plafond réel serait
  multiplié par le nombre d'instances. À déplacer dans Redis avant de scaler.
- Rapprochement comptable : aucun état ne compare encore la somme des transactions
  aux soldes. À ajouter avant la mise en production réelle.
- App mobile native → ajouter FCM à côté du web push.
- SMS transactionnel (OTP) : opérateur et coût à arbitrer.
- Réutilisation d'une question déjà servie : délai de quarantaine à définir.
- Parrainage : hors périmètre initial.
- Gains versés automatiquement à la clôture, parties signalées comprises : la
  revue des signalements doit se faire pendant le compte à rebours (la validation
  manuelle des retraits reste le second filet). Envisager un blocage de la
  finalisation tant que des signalements ne sont pas traités.
- Limitation de débit sur `/api/game/start` et corrélation IP / appareil entre
  comptes : pas encore faites (IP et user-agent sont déjà enregistrés par partie).
- Notifications de fin de quiz et de gains : à brancher avec le module
  notification.
