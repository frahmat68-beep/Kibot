# Supabase Infra

This folder contains the control-plane schema for KiCryp.

## Apply Migrations

Apply the SQL files in `migrations/` in lexical order.

## Important Notes

- The app should use normal authenticated user credentials, not `service_role`.
- Direct writes to critical coordination tables should be minimized.
- Lease and execution reservations should go through RPC functions from `0003_functions.sql`.
- RLS policies assume a single private owner account.

