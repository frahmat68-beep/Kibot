# Test Plan

## Unit Tests

- Lease takeover only allowed after expiry and clean reconciliation.
- Risk engine blocks new entries after hard daily loss breach.
- Pair selector rejects thin or expensive pairs.
- Reconciliation blocks on unmatched fills.

## Integration Tests

- Device command changes propagate into local state repositories.
- Fake exchange can simulate order submit/cancel/fill loops.
- Supabase RPC functions enforce term ownership and lease exclusivity.

## Failure Scenario Tests

- Android restart while master
- Mac auto takeover after expired heartbeat
- Split-brain detection path
- Partial fill during takeover
- Deposit/top-up in the middle of the day causing rebase pause

## Manual Validation Before Live Use

- Verify API key has no withdraw permission.
- Verify encrypted credential bundle can be recovered on both devices.
- Verify command buttons do not create duplicate actions.
- Verify takeover does not allow new entry if reconciliation is dirty.

