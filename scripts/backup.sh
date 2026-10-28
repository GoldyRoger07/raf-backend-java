#!/usr/bin/env bash
# =============================================================================
# RAF - Sauvegarde de la base MySQL et des images (volume media-data).
#
# A lancer depuis le dossier raf-backend-java du serveur, par cron, par exemple
# chaque nuit a 3 h :
#
#   0 3 * * * cd /opt/raf/raf-backend-java && ./scripts/backup.sh >> /var/log/raf-backup.log 2>&1
#
# Variables (facultatives) :
#   BACKUP_DIR        dossier local des archives          (defaut : ./backups)
#   BACKUP_KEEP_DAYS  jours de conservation en local      (defaut : 14)
#   BACKUP_REMOTE     destination rclone hors du serveur  (ex. : b2:raf-backups)
#                     Sans copie hors du serveur, une panne de disque emporte
#                     la base ET ses sauvegardes.
#
# Restauration (a tester au moins une fois, sur une machine de preproduction) :
#   gunzip -c raf-db-<date>.sql.gz | docker compose exec -T mysql \
#       sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
#   docker run --rm -v raf-backend-java_media-data:/data -v "$PWD":/backup alpine \
#       tar xzf /backup/raf-media-<date>.tgz -C /data
# =============================================================================
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
PROJECT="$(basename "$PWD")"

mkdir -p "$BACKUP_DIR"

# --single-transaction : instantane coherent sans bloquer les ecritures (InnoDB).
# Le mot de passe reste dans le conteneur, jamais sur la ligne de commande de l'hote.
echo "[$(date -u +%FT%TZ)] Sauvegarde de la base"
docker compose exec -T mysql sh -c \
    'exec mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers "$MYSQL_DATABASE"' \
    | gzip > "$BACKUP_DIR/raf-db-$STAMP.sql.gz.part"
mv "$BACKUP_DIR/raf-db-$STAMP.sql.gz.part" "$BACKUP_DIR/raf-db-$STAMP.sql.gz"

echo "[$(date -u +%FT%TZ)] Sauvegarde des images"
docker run --rm \
    -v "${PROJECT}_media-data:/data:ro" \
    -v "$(cd "$BACKUP_DIR" && pwd):/backup" \
    alpine:3.22 tar czf "/backup/raf-media-$STAMP.tgz" -C /data .

if [ -n "${BACKUP_REMOTE:-}" ]; then
    echo "[$(date -u +%FT%TZ)] Copie vers $BACKUP_REMOTE"
    rclone copy "$BACKUP_DIR/raf-db-$STAMP.sql.gz" "$BACKUP_REMOTE"
    rclone copy "$BACKUP_DIR/raf-media-$STAMP.tgz" "$BACKUP_REMOTE"
else
    echo "ATTENTION : BACKUP_REMOTE non defini, aucune copie hors du serveur." >&2
fi

find "$BACKUP_DIR" -name 'raf-*' -type f -mtime +"$BACKUP_KEEP_DAYS" -delete
echo "[$(date -u +%FT%TZ)] Termine : raf-db-$STAMP.sql.gz, raf-media-$STAMP.tgz"
