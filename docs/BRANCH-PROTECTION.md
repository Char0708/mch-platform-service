# Main branch gate

Target repository rule for `main` after the initial baseline commit:

- require a pull request before merge;
- require the `verify` job from `.github/workflows/ci.yml`;
- require the branch to be up to date before merge;
- block force pushes and branch deletion;
- require conversation resolution;
- do not permit failed or skipped checks to satisfy the gate.

The repository currently has only one authorized owner, `@Char0708`. A required approving review cannot provide independent database, security or release review while that remains true, so approval count and CODEOWNERS review are not claimed as satisfied controls. Repository rules must be configured and exported through GitHub administration before the gate is marked verified.

The first baseline commit is the bootstrap exception because the repository starts empty. It does not authorize later direct pushes to `main`.
