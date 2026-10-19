-- =============================================================================
-- Le journal d'audit s'ecrit dans une transaction independante, pour que la trace
-- survive a l'annulation de l'operation tracee (une tentative de connexion echouee
-- doit rester visible). Cette independance rend la cle etrangere intenable :
--   - a l'inscription, l'utilisateur n'est pas encore valide en base, donc la
--     transaction d'audit ne le voit pas et la contrainte echoue ;
--   - une trace ne doit de toute facon pas disparaitre avec l'acteur.
-- La colonne reste indexee pour les recherches par acteur.
-- =============================================================================

ALTER TABLE audit_logs DROP FOREIGN KEY fk_audit_logs_actor;
