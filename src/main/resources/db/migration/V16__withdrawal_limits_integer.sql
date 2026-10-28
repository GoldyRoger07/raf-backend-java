-- =============================================================================
-- RAF - Limites de retrait en entiers
--
-- V12 a converti les limites de retrait en HTG en multipliant par un taux
-- DECIMAL : la valeur ecrite ressemble a '1000.000000000000'. SettingsService
-- n'accepte qu'un entier, la rejette et retombe sur la valeur par defaut, avec
-- une erreur dans les logs a chaque retrait. On arrondit a l'entier.
-- =============================================================================

UPDATE app_settings
SET setting_value = CAST(ROUND(CAST(setting_value AS DECIMAL(20, 6))) AS CHAR)
WHERE setting_key IN ('withdrawal.min_htg', 'withdrawal.max_htg_per_day')
  AND setting_value REGEXP '^[0-9]+[.][0-9]+$';
