-- Codes de retrait par email, en secours si les SMS sont indisponibles.
--
-- Interrupteur app.withdrawal_codes_by_email (coupe par defaut, reserve au SUPER_ADMIN,
-- voir WithdrawalCodeService) : actif, la confirmation d'un retrait, le code de remise
-- au guichet et le retrait saisi au guichet partent a l'email du compte, et un email
-- confirme suffit pour retirer.
--
-- otp_channel garde le canal du code qui a confirme le retrait (SMS ou EMAIL) : le
-- back-office signale les retraits confirmes par email. NULL pour les retraits anterieurs.
ALTER TABLE withdrawal_requests
    ADD COLUMN otp_channel VARCHAR(10) NULL;
