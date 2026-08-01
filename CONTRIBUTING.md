# Contributing

This repository is currently limited to a minimal, non-business engineering sample.

1. Create changes on a branch and keep `main` releasable.
2. Run the boundary scan and Maven verification before proposing a change.
3. Do not add health records, login, permissions, configuration publication, medical rules, production adapters or business API paths before the corresponding DG2 slice is approved.
4. Database changes must be forward-only, reviewed and represented by immutable migrations.
5. A green engineering build does not close user, medical, privacy, interface, production or operations gates.
