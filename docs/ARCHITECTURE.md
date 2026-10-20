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
| Durée d'une partie | N questions × chrono par question ; échéance **faisant autorité côté serveur** |
| Temps par question | Chrono par question défini par quiz (défaut 5 s). On passe à la suivante dès la réponse ou à l'expiration ; une question expirée compte comme fausse |
| Déroulement | Choisi **par quiz** : `BATCH` (tirage complet, soumission groupée) ou `SEQUENTIAL` (une question à la fois, chrono imposé par le serveur) — voir §4 |
| Barème | Points fixes par bonne réponse, pas de malus |
| Anti-triche | **Révélation différée** : questions envoyées sans corrigé, partie hors-ligne, score recalculé au submit. Après la clôture, le joueur voit seulement juste / fausse / sans réponse par question : jamais l'énoncé ni la bonne réponse (corrigé détaillé réservé au back-office) |
| Modes de jeu | **En groupe** (`GROUP`) : tickets, classement, récompenses par rang. **Solo** (`SOLO`) : partie payée en crédits au lancement, prix fixe pour un sans-faute, une partie par sujet et par jour — voir §4.1 |
| Tickets | Mode En groupe uniquement. 1 ticket = 1 partie. Plafond **par joueur** et plafond **total** par quiz (null = illimité) |
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

→ entiers, jamais de flottant. **Deux unités** (depuis V12) :
- `credit_balance` en **crédits** : monnaie de jeu achetée, non retirable. Sert à payer
  les tickets et les parties solo, dont les prix restent en crédits. Le taux
  `credit.htg_rate` ne sert qu'aux recharges (HTG → crédits) et à l'affichage.
- `winnings_balance` en **HTG** : gains retirables, c'est-à-dire une dette réelle de RAF.
  Récompenses par rang, prix d'un sans-faute, retraits, leurs limites
  (`withdrawal.min_htg`, `withdrawal.max_htg_per_day`) et les ajustements des gains
  sont en gourdes. Changer la valeur du crédit ne réévalue donc jamais ce que RAF doit.

**wallet_transactions** — journal immuable, jamais modifié après insertion

| Colonne | Notes |
|---|---|
| id, wallet_id | |
| type | TOPUP, TICKET_PURCHASE, SOLO_ENTRY, PRIZE, WITHDRAWAL, REFUND, ADJUSTMENT |
| balance_type | CREDIT ou WINNINGS |
| amount | BIGINT signé (+ crédit, − débit), dans l'unité du solde : crédits (CREDIT) ou HTG (WINNINGS) |
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

**categories** — `id, name, slug UNIQUE, parent_id, media_id, active, group_enabled, solo_enabled`

→ `group_enabled` / `solo_enabled` : le sujet est proposé, ou non, dans chaque mode de jeu (interrupteurs admin).

**questions**

`id, category_id, text TEXT, type (SINGLE_CHOICE | TRUE_FALSE), difficulty (EASY | MEDIUM | HARD), media_id, explanation, status (DRAFT | PUBLISHED | ARCHIVED), lang CHAR(2) DEFAULT 'fr', source (MANUAL | IMPORT | AI), ai_model, times_served INT, times_correct INT, created_by, created_at`

→ `times_served` / `times_correct` permettent de recalibrer la difficulté réelle.
→ `source` : origine de la question (saisie, import CSV, IA) ; `ai_model` garde le modèle qui l'a générée. Les questions antérieures à V13 sont `MANUAL`.

**ai_generation_runs** — `id, actor_id, category_id, provider, model, requested, created_count, duplicates, rejected, error, duration_ms, created_at` : une ligne par demande de génération, réussie ou non (plafond journalier, comparaison des modèles).

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
| game_mode | GROUP ou SOLO |
| solo_prize_credits | SOLO : prix d'un sans-faute (ticket_price_credits = prix d'une partie) |
| play_mode | BATCH ou SEQUENTIAL (défaut SEQUENTIAL ; toujours SEQUENTIAL en SOLO) |
| question_duration_seconds | chrono par question, défaut 5 |
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
| ticket_id | UNIQUE — 1 ticket = 1 session (NULL en SOLO) |
| game_mode, category_id | mode et sujet joué, copiés du quiz |
| solo_day | SOLO : jour civil (Haïti) ; UNIQUE(user_id, category_id, solo_day) |
| entry_price_credits, entry_transaction_id | SOLO : prix de la partie et son débit |
| prize_credits, won, prize_transaction_id | SOLO : prix d'un sans-faute, et son versement |
| status | IN_PROGRESS, SUBMITTED, EXPIRED, INVALIDATED |
| play_mode, question_duration_seconds | copiés depuis le quiz au démarrage |
| duration_seconds | N × chrono par question |
| started_at | **fait autorité, sert au départage** |
| server_deadline_at | BATCH : started_at + durée + marge réseau. SEQUENTIAL : started_at + N × (chrono + marge par question) + marge réseau |
| submitted_at, client_duration_ms | |
| score, correct_count, answered_count | recalculés côté serveur |
| ip, user_agent | détection d'abus |

**game_session_questions** — le tirage figé du joueur

`id, session_id, question_id, position, correct_choice_id, served_at`

→ `served_at` (SEQUENTIAL) : envoi de la question au joueur, départ de son chrono.

→ snapshot : une modification ultérieure de la question ne change pas le score déjà calculé.

**game_answers** — `id, session_id, question_id, choice_id, is_correct, elapsed_ms, points_awarded`

→ `choice_id` NULL : question expirée sans réponse (comptée fausse).

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

→ Back-office, page **Paramètres** (super-administrateur) : `GET /api/admin/settings`,
`PUT /api/admin/settings/{key}` `{ value }`. Seuls les paramètres existants, en
entier, au moins égaux à leur minimum (`SettingKeys.minValue` : 1 pour la valeur du
crédit, les plafonds de retrait, le ratio du vivier…). Chaque modification est
tracée (`SETTING_UPDATED`). Changer `credit.htg_rate` change la valeur en gourdes
des crédits de jeu en circulation (les gains, en HTG, ne bougent pas) : la page
affiche l'impact et demande confirmation.

---

## 4. Déroulé d'une partie (anti-triche)

**Modèle retenu : révélation différée.** Le corrigé ne quitte jamais le serveur
pendant la partie. Chaque question a son propre chrono (`question_duration_seconds`,
par quiz) : on passe à la suivante dès que le joueur répond, ou à l'expiration du
chrono. Le quiz choisit l'un des deux déroulements suivants (`play_mode`).

#### BATCH — tirage complet, soumission groupée

```
POST /api/game/start           (ticket valide requis)

  serveur : tire N questions du pool, fige le tirage, pose started_at + deadline
  → { sessionId, playMode: BATCH, questionDurationSeconds, totalQuestions,
      questions: [ { id, texte, choix: [ { id, texte } ] } ], current: null }
                                            ↑ aucun indicateur de bonne réponse

  Le front joue en LOCAL, question par question, avec le chrono par question.
  À chaque clic il affiche « réponse enregistrée », sans ✓ ni ✗.

POST /api/game/submit
  → { sessionId, reponses: [ { questionId, choixId | null, elapsedMs } ] }
  ← { score, correctCount, answeredCount }     ← PAS le détail des réponses

  Une question expirée est envoyée avec choixId null et elapsedMs = instant
  de l'expiration.

  Validations serveur — toute violation ⇒ session INVALIDATED :
    1. session IN_PROGRESS et appartenant à l'appelant
    2. maintenant ≤ server_deadline_at (+ marge réseau)
    3. la réponse n porte sur la question n du tirage
    4. elapsedMs croissants, ≤ durée totale, écart entre deux réponses ≤ chrono
    5. dernier elapsedMs ≤ temps réellement écoulé côté serveur
    6. score recalculé depuis correct_choice_id — la valeur du client est ignorée
```

**Limite assumée.** Les temps par question sont *déclarés* par le client : le
serveur ne borne réellement que la durée totale. Un client trafiqué voit tout le
tirage dès le départ et peut répartir le temps à sa guise (passer 1 s sur les
questions faciles pour en garder davantage sur les difficiles). À réserver aux
quiz où cet enjeu est acceptable.

#### SEQUENTIAL — une question à la fois, chrono imposé par le serveur

```
POST /api/game/start
  → { sessionId, playMode: SEQUENTIAL, totalQuestions, questions: [],
      current: { question, endsAt, remainingMs } }     ← la question 1 seule

POST /api/game/{sessionId}/answer
  → { questionId, choiceId | null }      null = passer / chrono expiré
  ← { accepted, answeredQuestions, totalQuestions,
      next: { question, endsAt, remainingMs } | null,
      result: { score, ... } | null }     ← résultat à la dernière question
```

- Le serveur horodate l'envoi de chaque question (`served_at`). Une réponse
  arrivée après `served_at + chrono + game.question_grace_ms` (défaut 1 500 ms)
  est enregistrée **sans choix** (`accepted: false`) et la question compte fausse.
- La question n+1 n'est envoyée qu'avec la réponse à la question n : le joueur ne
  voit jamais une question avant son tour, ni ne peut répartir son temps.
- `questionId` rend l'envoi rejouable : un nouvel essai réseau d'une réponse déjà
  enregistrée renvoie l'état courant sans rien appliquer à la question suivante.
  Un choix étranger à la question invalide la partie.
- Reprise (`/start` à nouveau) : sans effet de bord, elle renvoie la question en
  cours et son temps restant (0 si elle a expiré pendant l'absence ; le client
  envoie alors une réponse vide pour passer à la suivante).
- Partie abandonnée (onglet fermé) : à l'échéance, `GameScheduler` la corrige
  avec les réponses déjà données — elles ont été validées une à une. En BATCH, au
  contraire, une partie non soumise expire sans compter.

#### Corrigé

`GET /api/game/{sessionId}/review` (joueur, quiz terminé ou solo fermé) ne renvoie
que le résultat de chaque question : `{ position, result: CORRECT | WRONG |
UNANSWERED }`. Ni énoncé, ni propositions, ni bonne réponse : les questions
restent dans le vivier et peuvent resservir, elles ne doivent pas circuler avec
leurs réponses. Le filtrage est fait par le serveur — le masquer dans
l'application ne protégerait rien. Le corrigé détaillé (énoncés, choix, bonne
réponse, temps) reste servi par `GET /api/admin/game/sessions/{id}/review`, pour
juger une partie signalée.

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

**À durcir ensuite** : limitation de débit sur `/game/start` et `/game/{id}/answer`, détection des temps de
réaction impossibles (< 400 ms répétés), corrélation IP / appareil entre comptes.

---

### 4.1 Mode Solo

Un quiz solo est créé par l'admin (`POST /api/admin/quizzes/solo`) : un sujet
**proposé en Solo**, un prix par partie, un prix pour un sans-faute, un nombre de
questions et un chrono. Il se joue toujours **question par question**
(SEQUENTIAL) : chaque partie engage de l'argent, le joueur ne doit voir aucune
question avant son tour.

```
DRAFT ──publish──▶ RUNNING (vivier figé, ouvert en continu) ──close──▶ FINISHED
```

- **Lancement** (`POST /api/game/start`, même endpoint qu'en groupe) : débit de
  `ticket_price_credits` sur le solde CREDIT (`SOLO_ENTRY`). Pas de ticket. Email
  vérifié exigé, comme à l'achat d'un ticket.
- **Une partie par sujet et par jour** (jour civil d'Haïti), tous quiz solo
  confondus. La clé d'idempotence du débit porte la règle
  (`solo:{joueur}:{sujet}:{jour}`) : deux clics simultanés se sérialisent sur le
  verrou du portefeuille et ne paient qu'une fois. La contrainte
  `uk_game_sessions_solo_day` la double en base. Un rechargement reprend la
  partie en cours. `GET /api/game/solo/played-today` liste les sujets déjà joués.
- **Sans-faute** = toutes les questions du tirage justes : le prix est versé
  aussitôt sur le solde WINNINGS (`PRIZE`, clé `solo-prize:session:{id}`). Le
  joueur sait s'il a gagné, pas quelle réponse était fausse : le corrigé reste
  verrouillé jusqu'à la fermeture du quiz.
- Pas de classement, de récompenses par rang, ni de clôture déclenchée par un
  score. Une partie gagnée ne peut plus être invalidée (le prix est parti).
- Désactiver un sujet en Solo bloque aussitôt les nouvelles parties sur ce sujet.
  En groupe, l'interrupteur est vérifié à la création, aux sujets du vote et à la
  publication, mais n'affecte pas un quiz déjà publié (tickets vendus).
- Le rapprochement contrôle le débit d'entrée (`SOLO_ENTRY_DEBIT`) et le versement
  du prix (`SOLO_PRIZE_PAYMENT`) de chaque partie solo.

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
| POST | `/api/game/submit` | joueur, partie BATCH |
| POST | `/api/game/{sessionId}/answer` | joueur, partie SEQUENTIAL |
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
2. *Le submit (et `/answer`) ne charge jamais le quiz* : mode, chrono et bareme sont copies dans la
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

**Mode Solo (V11).** `V11__solo_mode.sql` ajoute les interrupteurs de mode sur
`categories`, `game_mode` et `solo_prize_credits` sur `quizzes`, et sur
`game_sessions` les colonnes solo (ticket rendu facultatif, jour, débit d'entrée,
prix). Voir §4.1.

| Methode | Endpoint | Acces |
|---|---|---|
| POST / PUT | `/api/admin/quizzes/solo` , `/api/admin/quizzes/solo/{id}` | admin |
| POST | `/api/admin/quizzes/{id}/publish` (solo : ouverture directe) | admin |
| POST | `/api/admin/quizzes/{id}/close` | admin, quiz solo |
| POST | `/api/admin/quizzes/{id}/duplicate` | admin, tout quiz : copie en brouillon (règles, récompenses, sujets du vote), sans l'historique |
| GET | `/api/admin/quizzes?status=&mode=` | admin |
| GET | `/api/quizzes?mode=` (GROUP ou SOLO) | joueur |
| GET | `/api/categories?mode=` (GROUP ou SOLO) | joueur |
| PATCH | `/api/categories/{id}/modes` | admin |
| GET | `/api/game/solo/played-today` | joueur |

**Chrono par question (V10).** `V10__question_timer.sql` remplace
`quizzes.game_duration_seconds` par `play_mode` + `question_duration_seconds`.
Les quiz existants passent en BATCH, leur duree repartie entre les questions
(minimum 3 s). Les parties existantes gardent un "chrono par question" egal a
leur duree totale, ce qui neutralise le nouveau controle pour elles. Ajoute
`game_session_questions.served_at`, rend `game_answers.choice_id` nullable et
cree le parametre `game.question_grace_ms`. Le mode est fige dans la partie au
demarrage : tous les joueurs d'un quiz jouent selon les memes regles.

### Lot 7 - Paiements (a venir)

**Exigence : les depots et retraits manuels restent possibles depuis le
back-office, en plus des connecteurs MonCash / NatCash** (decision du
2026-10-19). Etat actuel et ce qu'il reste a ajouter :

| Operation | Existe | A ajouter |
|---|---|---|
| Depot manuel | `POST /api/admin/wallet/topups/manual` (provider `MANUAL`, reference idempotente) | rien d'obligatoire |
| Retrait demande par le joueur, paye en especes | **livre** (voir ci-dessous) | — |
| Retrait saisi par l'admin pour un joueur (guichet) | **livre** (voir ci-dessous) | — |

#### Retrait en especes demande par le joueur (livre)

| Methode | Endpoint | Acces |
|---|---|---|
| POST | `/api/wallet/withdrawals` avec `provider: MANUAL` | joueur |
| POST | `/api/admin/wallet/withdrawals/{id}/pickup-code` | admin |
| POST | `/api/admin/wallet/withdrawals/{id}/paid` avec `pickupCode` | admin |

Le joueur demande un retrait `MANUAL` sans numero de reception (OTP de retrait
habituel, gains geles aussitot, frais `withdrawal.counter_fee_htg`). Un admin
l'approuve comme les autres. Au guichet, l'agent envoie au joueur present un code
de remise (`CASH_PICKUP`, SMS annoncant le montant net) ; la remise (`paid`)
**exige ce code** — sans lui, quiconque se presenterait sous le nom du joueur
repartirait avec ses gains. Pour MonCash / NatCash, `paid` exige toujours la
reference de l'operateur. L'objet d'OTP `CASH_PICKUP` est distinct de
`COUNTER_WITHDRAWAL` : un code de remise ne peut pas confirmer un retrait saisi par
l'agent pour un autre montant (verifie par un test).

#### Retrait au guichet (livre)

| Methode | Endpoint | Acces |
|---|---|---|
| POST | `/api/admin/wallet/users/{userId}/counter-withdrawals` | admin |
| POST | `/api/admin/wallet/counter-withdrawals/{id}/confirm` | admin |
| POST | `/api/admin/wallet/counter-withdrawals/{id}/cancel` | admin |

**Le code OTP du joueur est exige.** Sans lui, un administrateur — ou un compte
administrateur compromis — pourrait vider les gains de n'importe quel joueur en
son absence.

**Deux temps, pour que le code autorise un montant precis.** La saisie (montant en
HTG) applique les memes controles que le retrait en ligne (telephone verifie,
minimum, plafond journalier, frais, solde), cree la demande en `AWAITING_OTP`
**sans rien debiter**, et envoie au joueur un SMS qui annonce le montant net. La
confirmation, avec le code donne par le joueur, debite les gains en type
`WITHDRAWAL` et passe directement en `PAID` : les especes sont remises sur place.
Elle est idempotente (verrou sur la demande, cle `withdrawal:{id}`).

Une nouvelle saisie annule celle encore en attente pour ce joueur. Comme un nouvel
OTP invalide le precedent, le seul code valable correspond toujours a la seule
demande confirmable — un code ne peut pas servir a confirmer un autre montant.
L'objet `COUNTER_WITHDRAWAL` de l'OTP est distinct de `WITHDRAWAL` : un code
demande pour un retrait en ligne ne sert pas au guichet.

Migration `V7__counter_withdrawal.sql` : `destination_number` devient nullable
(pas de numero pour des especes), ajout de `initiated_by_admin_id` et `note`, et
du parametre `withdrawal.counter_fee_htg` (25 HTG, comme le retrait en ligne — a
ajuster). Le plafond journalier ignore desormais les demandes `AWAITING_OTP` et
`CANCELLED`, ou rien n'a ete debite.

Toutes ces operations passent par `WalletService` et laissent une trace
d'audit, comme les autres mouvements d'argent.

### Rapprochement comptable (livre)

| Methode | Endpoint | Acces |
|---|---|---|
| POST | `/api/admin/reconciliation/runs` | admin |
| GET | `/api/admin/reconciliation/runs` | admin |
| GET | `/api/admin/reconciliation/runs/{id}` | admin |

Execute chaque nuit a 3 h, heure d'Haiti (`app.reconciliation.cron`), et a la
demande. Migration `V8__reconciliation.sql` : tables `reconciliation_runs` et
`reconciliation_findings`, et index sur les colonnes de lien vers les
transactions.

| Controle | Verifie |
|---|---|
| `LEDGER_MISMATCH` | chaque solde (credits, gains) = somme de ses transactions |
| `BALANCE_CHAIN_BREAK` | chaque `balance_after` = precedent + montant |
| `TICKET_DEBIT` / `TICKET_REFUND` | ticket ↔ debit, ticket rembourse ↔ remboursement |
| `TOPUP_CREDIT` | recharge confirmee ↔ credit |
| `WITHDRAWAL_DEBIT` / `WITHDRAWAL_REFUND` | retrait ↔ gel des gains, rejet ↔ restitution |
| `PRIZE_PAYMENT` | rang recompense ↔ versement |
| `UNEXPECTED_MOVEMENT` | aucun mouvement sur une operation qui ne devait pas en produire |
| `ORPHAN_TRANSACTION` | aucun mouvement sans operation qui le reference (hors `ADJUSTMENT`) |

Les liens verifient le type, le solde, le montant **et le proprietaire** du
mouvement. Chaque controle est une seule instruction SQL, lue par InnoDB sur un
instantane coherent ; comme une operation et son mouvement sont toujours commites
ensemble, le rapprochement peut tourner en pleine activite sans faux positif.

Il **ne corrige rien** : un ecart revele une erreur de code ou une modification
manuelle de la base, et merite une enquete. Il alerte (journal d'erreurs, audit
`RECONCILIATION_DISCREPANCIES`) et conserve le detail des 500 premiers ecarts. Les
totaux de chaque execution donnent aussi le passif de la plateforme : la somme des
gains retirables dus aux joueurs.

> Premier passage sur la base de developpement : il a retrouve seul la trace du
> bug de concurrence du lot 3 (21 septembre, avant correction) — 9 debits de 100
> enregistres sur un solde de 500, avec des `balance_after` en double.

### Notifications (livre)

| Methode | Endpoint | Acces |
|---|---|---|
| GET | `/api/notifications?unreadOnly=` | authentifie |
| GET | `/api/notifications/unread-count` | authentifie |
| POST | `/api/notifications/{id}/read` , `/api/notifications/read-all` | authentifie |
| GET / PUT | `/api/notifications/preferences` | authentifie |
| GET | `/api/notifications/push/public-key` | authentifie |
| POST / DELETE | `/api/notifications/push/subscriptions` | authentifie |

Migration `V9__notifications.sql` : `notifications`, `notification_preferences`,
`push_subscriptions`. Trois canaux : **in-app** (toujours enregistre, c'est
l'historique), **email** (Resend, ou console en dev) et **push navigateur** (Web
Push signe VAPID ; sans cles, journalise). Chaque type a ses canaux par defaut ;
le joueur coupe ou active l'email et le push type par type.

| Type | Declencheur | Canaux par defaut |
|---|---|---|
| `QUIZ_SCHEDULED` | seuil de joueurs atteint (detenteurs de ticket) | push |
| `QUIZ_STARTED` | demarrage du quiz | push |
| `QUIZ_CLOSING` | score minimum depasse, compte a rebours | push |
| `QUIZ_CANCELLED` | annulation et remboursement (tous les acheteurs) | email, push |
| `PRIZE_WON` | classement fige, rang recompense | email, push |
| `QUIZ_RESULTS` | classement fige, rang non recompense | push |
| `TOPUP_CONFIRMED` | recharge confirmee | push |
| `WITHDRAWAL_APPROVED` / `_PAID` / `_REJECTED` | traitement d'un retrait (guichet compris) | push ; email pour verse et refuse |
| `ADMIN_RECONCILIATION_ALERT` | ecart ou echec du rapprochement (administrateurs) | email |

L'email est reserve a l'argent et a ce que le joueur doit savoir hors de
l'application : un email a chaque detenteur de ticket au demarrage d'un quiz serait
du bruit, et couterait cher.

**Rien ne part avant le commit.** L'envoi est enregistre aupres de la transaction
metier et n'a lieu que si elle est validee (verifie par un test) : un quiz dont
l'annulation echoue n'annonce pas de remboursement. Il se fait ensuite sur une file
dediee (`NotificationExecutor`), separee de celle de l'audit, sans transaction
englobante : aucune connexion n'est tenue pendant les appels a Resend ou au service
push. Un abonnement revoque par le navigateur (404 / 410) est supprime.


### Back-office : endpoints dedies (phase 1)

| Methode | Endpoint | Acces |
|---|---|---|
| GET | `/api/admin/dashboard` | admin |
| GET | `/api/admin/players?query=` , `/api/admin/players/{id}` | admin |
| GET | `/api/admin/wallet/withdrawals?status=` ou `?userId=` | admin |

Le back-office est une application Angular (projet `raf-frontend-ssr`, routes
`/admin`, rendues dans le navigateur uniquement). Le tableau de bord agrege en SQL
direct (joueurs, quiz par statut, argent en circulation, activite sur 30 jours,
travail en attente, dernier rapprochement). La recherche de joueurs, ouverte aux
admins et non au seul super-admin, trouve un joueur par email, nom ou telephone
saisi sans indicatif ; elle ne permet de rien modifier. La liste des retraits
renvoie le joueur avec chaque demande, assemble dans la transaction
(`AdminWithdrawalResponse`).

**Corrige en phase 2 :** la regle `/api/admin/questions/**` (MODERATOR, ADMIN,
SUPER_ADMIN) precede desormais celle de `/api/admin/**` ; un test HTTP par role
le verifie.

### Back-office : phase 3

| Methode | Endpoint | Acces |
|---|---|---|
| GET | `/api/admin/game/sessions/flagged` | admin |
| GET | `/api/admin/audit` , `/api/admin/audit/filters` | admin |
| GET | `/api/admin/users?query=&staffOnly=` | super-admin |

Le journal d'audit se consulte en lecture seule, filtrable par action, type et
identifiant d'objet, et auteur ; les noms des auteurs sont resolus en une requete
par page. Les parties signalees sont listees tous quiz confondus, tant que les
gains ne sont pas verses (au-dela, l'invalidation est refusee).

**Garde-fous des comptes.** Un super-administrateur ne peut modifier ni ses propres
roles ni son propre statut depuis la gestion des comptes (`OWN_ACCOUNT_LOCKED`) :
une erreur de manipulation le priverait de ses droits sans recours. Un compte
garde au moins un role (`ROLES_REQUIRED`), et le dernier super-administrateur
actif ne peut pas etre retrogade ni suspendu (`LAST_SUPER_ADMIN`, defense en
profondeur : avec la premiere regle, ce cas suppose deja une anomalie).

### Generation de questions par IA

Un moyen de plus d'alimenter la banque, a cote de la saisie et de l'import CSV.

| Methode | Endpoint | Acces |
|---|---|---|
| GET | `/api/admin/questions/ai/status` | moderateur, admin |
| POST | `/api/admin/questions/ai/generate` | moderateur, admin |
| GET | `/api/admin/questions?source=AI` | moderateur, admin |

**Independant du fournisseur.** Le service ne connait que l'interface
`QuestionGenerator` ; `app.ai.provider` choisit l'adaptateur :

| `AI_PROVIDER` | Adaptateur | Notes |
|---|---|---|
| `none` (defaut) | desactive | le back-office explique comment configurer |
| `anthropic` | API Claude | `AI_MODEL` par defaut `claude-sonnet-5-5` ; format impose par un outil a appel force |
| `openai` | toute API compatible OpenAI | `AI_BASE_URL` choisit le fournisseur (OpenAI, Mistral, DeepSeek, Groq, OpenRouter, Ollama local) ; mode `json_object` |

Variables : `AI_PROVIDER`, `AI_MODEL`, `AI_API_KEY`, `AI_BASE_URL`, `AI_TIMEOUT` (180 s).
La consigne (`GenerationPrompt`) est la meme pour tous les modeles ; les questions
deja dans la categorie (150 plus recentes) sont envoyees pour eviter les doublons.

**Rien n'est cru sur parole.** Chaque question renvoyee est verifiee : 2 a 6
propositions distinctes, exactement une bonne reponse, longueurs ; le type se
deduit du nombre de propositions et doit correspondre au type demande ; une
difficulte demandee l'emporte. Les propositions d'un QCM sont melangees (les
modeles placent volontiers la bonne en premier). Une question en double est
ignoree, une question fautive est rejetee avec son motif. Les questions valides
sont enregistrees **en brouillon** (`source = AI`), chacune dans sa transaction :
elles n'entrent dans le vivier qu'apres relecture et publication par un humain.

**Garde-fous.** Parametres `ai.max_questions_per_request` (30) et
`ai.max_requests_per_day` (20, jour d'Haiti) ; chaque demande, meme en echec,
compte et est tracee (`ai_generation_runs`, audit `QUESTIONS_GENERATED`).
L'appel au modele se fait hors transaction (jusqu'a une ou deux minutes).

### Tests d'integration

`./mvnw test` lance 64 tests contre le **MySQL local**, base `raf_test` (identifiants
de `.env.dev.properties`, URL modifiable par `TEST_DB_URL`). La base est videe
puis recreee par Flyway a chaque lancement ; **l'effacement refuse toute base dont
le nom ne finit pas par `_test`**, pour qu'une URL mal reglee ne puisse jamais vider
la base de developpement. Profil `test` : ordonnanceurs coupes
(`app.scheduling.enabled=false`), SMS captures en memoire, aucun delai entre deux
OTP.

| Classe | Couvre |
|---|---|
| `GameFlowTest` | lot 6 : reprise, double clic, anti-triche, cloture, classement, gains |
| `TicketConcurrencyTest` | lots 3 et 5 : solde jamais negatif, plafonds, idempotence, charge superieure au pool |
| `VoteConcurrencyTest` | lot 5 : votes simultanes tous comptes |
| `CounterWithdrawalTest` | lot 7 : retrait au guichet, OTP, retrait en ligne |
| `CashWithdrawalTest` | lot 7 : retrait en especes demande par le joueur |
| `NotificationTest` | canaux, preferences, aucun envoi si la transaction est annulee |
| `ReconciliationTest` | rapprochement : bilan propre, puis chaque falsification detectee |

**Defauts trouves par les tests et corriges.** Chacun a ete reproduit (le test
echoue sans la correction) avant d'etre corrige :

1. *Plafond total de tickets depasse sous concurrence* (4 vendus pour 2). Sous
   REPEATABLE READ, la recherche par cle d'idempotence, faite avant le verrou du
   quiz, figeait l'instantane : le comptage fait sous le verrou ignorait les achats
   commites pendant l'attente. **L'application tourne desormais en READ COMMITTED**
   (`spring.datasource.hikari.transaction-isolation`), ce qui supprime ce piege
   partout ; les verrous pessimistes restent la protection des invariants.
2. *Un meme code OTP utilisable deux fois en parallele* (deux retraits ouverts avec
   un code). La consommation est maintenant atomique (`OtpCodeRepository.consume`).
3. *Votes simultanes perdus* (1 sur 4 compte) : `lu + 1` perdait des votes, et
   l'insertion du vote suivie de l'increment interbloquait deux votants (meme
   promotion de verrou qu'au lot 6, regle 4). Le sujet est verrouille avant le vote
   et le compteur incremente par la base.
4. *Rejeux simultanes d'un achat refuses en 409* au lieu de renvoyer le ticket : la
   cle d'idempotence est re-verifiee sous le verrou du quiz.
5. *Epuisement du pool de connexions sous charge* (16 achats simultanes : 56 s et
   6 traces d'audit perdues sur 16). Chaque ecriture tenait sa connexion en
   attendant celle de l'audit (`REQUIRES_NEW`) ; meme les callbacks de fin de
   transaction s'executent avant la restitution de la connexion. L'audit et les
   statistiques de questions sont desormais ecrits par `AfterTransactionExecutor`,
   sur un thread a part, une fois la transaction terminee : le thread de la requete
   rend sa connexion sans en attendre une seconde. L'audit garde la trace des
   operations annulees (ecriture apres validation *ou* annulation). Contrepartie :
   l'entree d'audit apparait quelques millisecondes apres l'operation, et une
   trace en file d'attente est perdue si le processus meurt a cet instant (l'arret
   normal vide la file).

---

## 8. Points ouverts

- **Transactions imbriquees restantes** (`REQUIRES_NEW` appele en tenant deja une
  connexion), meme risque d'epuisement du pool mais sur des chemins peu
  concurrents : compteur d'essais OTP (`OtpAttemptRecorder`), revocation d'une
  famille de jetons, creation du portefeuille a la volee (`WalletProvisioner`),
  import CSV. A surveiller ; a traiter si un de ces chemins devient massif
  (par exemple des inscriptions en rafale).

- Cadre légal haïtien du jeu payant : licence, âge minimum 18+, jeu responsable
  (plafond de dépense, auto-exclusion), fiscalité des gains.
- `RateLimiter` compte en mémoire : sur plusieurs instances, le plafond réel serait
  multiplié par le nombre d'instances. À déplacer dans Redis avant de scaler.
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
- Notifications : le frontend doit fournir le service worker qui affiche les push
  (charge utile JSON : `title`, `body`, `url`, `type`, `data`). Pas encore de
  regroupement : un joueur detenteur de tickets sur plusieurs quiz recoit un push
  par evenement.
