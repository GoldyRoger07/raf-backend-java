# Suivi de mise en production

Etat au 2026-10-28 (analyse du backend, du site SSR et de l'app joueur).
Cocher chaque tache une fois faite et verifiee. Les 125 tests passent a cette date.

**Mise a jour du 2026-10-28 (soir)** : 126 tests passent. Production avec
`docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`
(Caddy en HTTPS, seuls 80 et 443 publies).

## 1. Bloquants

- [ ] **Operateur SMS reel.** `LoggingSmsSender` ne fait que journaliser le code.
      Or tout retrait exige un telephone verifie (`WithdrawalService`,
      `CounterWithdrawalService`) : sans SMS, aucun joueur ne peut retirer ses gains.
      Choisir le fournisseur (Twilio, Digicel, Natcom...), puis implementer `SmsSender`.
- [x] **URL du backend en dur dans le site.** Fait : `src/environments/environment.ts`
      (production : API sur la meme origine, `/api` relaye par Caddy) et
      `environment.development.ts` (ng serve : localhost). Build verifie sans localhost.
      En local Docker, le serveur Node relaie `/api` (`API_PROXY_URL`), teste.
  - [ ] Confirmer les domaines : `playerAppUrl` vaut `https://app.rafhaiti.com`
        (hypothese), `SITE_DOMAIN` / `APP_DOMAIN` dans `.env`.
- [ ] **Secrets et configuration de production.**
  - [ ] `.env.prod.properties` : `JWT_SECRET` (placeholder), `APP_FRONTEND_URL` (placeholder),
        `DB_URL` (pointe vers `secogroupe_app`, base d'un autre projet ?), retirer `RESEND_API_KEY`.
  - [ ] `.env` (Docker) : `APP_ENV=prod`, cles VAPID (`npx web-push generate-vapid-keys`),
        URL publiques, `CORS_ALLOWED_ORIGINS`, `FRONTEND_ALLOWED_HOSTS`, `MEDIA_PUBLIC_URL`.
  - [ ] Variables SMTP : `MAIL_MODE=smtp`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`,
        `MAIL_PASSWORD`, `MAIL_FROM`, `MAIL_FROM_NAME`.
  - [ ] Compte administrateur initial (`BOOTSTRAP_ADMIN_*`), puis vider ces valeurs.
- [ ] **HTTPS.** Ecrit, non teste (Docker indisponible sur le poste) :
      `Caddyfile` + service `caddy` dans `docker-compose.prod.yml`. Le nginx de l'app
      reprend l'IP reelle transmise par Caddy (`set_real_ip_from`). A valider sur le
      serveur : DNS des deux domaines, certificats obtenus, `docker compose config`.
- [ ] **Ne plus publier les ports internes.** Ecrit dans `docker-compose.prod.yml`
      (`ports: !reset []` sur mysql, backend, frontend, player), a verifier sur le
      serveur (`docker compose ps`). Avant : `ports` de `mysql` (3307) et de `backend` (8081). Le 8081 direct
      permet aussi de forger `X-Forwarded-For` et de contourner la limite de
      tentatives de connexion par IP (`RequestContext.clientIp`).
- [ ] **Sauvegardes automatiques** de MySQL et du volume `media-data`.
  - [x] Script `scripts/backup.sh` (mysqldump + archive des images, retention, copie rclone).
  - [ ] Cron sur le serveur, `BACKUP_REMOTE` configure (copie hors du serveur).
  - [ ] Restauration testee au moins une fois.

## 2. Importants

- [x] **Emails hors transaction.** Fait : `TransactionalMail`, envoi apres commit sur
      la file des notifications (verifie : thread `notification-1`).
      Avant : `VerificationService` et `AuthService` envoient l'email
      dans la transaction de la requete : avec SMTP (jusqu'a 10 s), une connexion du pool
      (10) reste bloquee. Envoyer apres le commit, sur une file dediee.
- [x] **Limiter `/api/auth/password/forgot` et `/api/auth/email/resend`.** Fait :
      3 demandes par adresse et 20 par IP et par heure (verifie : 4e demande -> 429).
      Avant : Aucun plafond
      aujourd'hui : envoi massif d'emails possible vers une adresse, ce qui nuit a la
      reputation du domaine.
- [x] **Migration V16 : limites de retrait illisibles.** Fait et verifie sur `raf`
      et `raf_test` (`100000.000...` -> `100000`). V12 a ecrit
      `withdrawal.min_htg` et `withdrawal.max_htg_per_day` en decimal
      (`1000.000000...`) ; `SettingsService` les rejette et retombe sur la valeur par
      defaut, avec une erreur dans les logs a chaque retrait.
- [x] **Supervision.** Actuator ajoute (seul `/actuator/health`, sans detail, SMTP exclu) ;
      healthcheck du service `backend` dans `docker-compose.prod.yml` (commande testee).
  - [ ] Alerte externe si le site tombe (UptimeRobot ou equivalent sur les deux domaines).
- [ ] **Recharges MonCash / NatCash.** Seule la recharge manuelle par un administrateur
      existe. Decider si c'est acceptable pour le lancement.
- [ ] **DNS du domaine d'envoi** (`rafhaiti.com`) : SPF, DKIM et DMARC pour le serveur SMTP.

## 3. Avant l'ouverture

- [ ] Commiter le passage a SMTP (Resend retire) et les changements du 2026-10-28
      (backend, site, nginx de l'app), puis fusionner les branches de travail
      dans `main` : `lot-6-7` (backend), `back-office` (site), `quiz-gratuit-instagram` (app joueur).
- [ ] Build de l'app joueur avec `apiUrl` renseigne si une version Android/iOS est publiee
      (`raf-ionic/src/environments/environment.prod.ts`).
- [ ] Repetition generale sur un serveur de preproduction : inscription, email de
      verification, SMS, achat de ticket, partie, classement, retrait, rapprochement.
- [ ] Verifier les logs apres la repetition (aucune erreur inattendue).
