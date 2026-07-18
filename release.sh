#!/usr/bin/env bash
#
# release.sh — one reviewed operation that prepares a release commit + tag, then STOPS.
#
# It bumps the pom version, stamps the CHANGELOG date, folds both into a single reviewed
# commit, and creates an annotated tag — but it NEVER pushes. Pushing the tag is the
# operator's deliberate point of no return (D-03): autoPublish=true means CI publishes to
# an immutable Central coordinate the moment the tag lands upstream.
#
# This script handles NO credentials. It echoes no secret, sets no secret, reads no secret.
#
# Usage: ./release.sh   (run from the repo root, on a clean main working tree)

set -euo pipefail

# --- single source of truth: change these two lines for a future release ---------------
VERSION="0.1.1"
TAG="v${VERSION}"
# ---------------------------------------------------------------------------------------

TODAY="$(date +%F)"

echo "==> Preparing release ${VERSION} (tag ${TAG})"

# 1) Version bump: 0.1.0-SNAPSHOT -> 0.1.0 as an explicit, reviewed change (D-04).
#    A lingering -SNAPSHOT silently routes the deploy to the snapshot repo (RESEARCH Pitfall 4).
echo "==> Bumping pom version to ${VERSION}"
./mvnw -q versions:set -DnewVersion="${VERSION}" -DgenerateBackupPoms=false

# 2) Release-time docs pass (D-06), folded into the SAME commit as the bump so the tagged
#    tree is fully consistent and nothing commits back after the tag.
echo "==> Stamping CHANGELOG [${VERSION}] date"
#    Stamp the CHANGELOG heading date (the real action for v0.1.0).
sed -i "s/^## \[${VERSION}\] - Unreleased/## [${VERSION}] - ${TODAY}/" CHANGELOG.md
#    README version sed, scoped to README.md ONLY (a no-op for v0.1.0 — README already reads
#    ${VERSION} — but kept so future releases update the install snippet in the same ritual).
#    It touches only the <version> line and the "Maven coordinate:" prose, never docs/ or the
#    CHANGELOG footer links.
sed -i "s#<version>[0-9][0-9.]*</version>#<version>${VERSION}</version>#" README.md
sed -i "s#\(io.github.pimak:logback-ntfy:\)[0-9][0-9.]*#\1${VERSION}#g" README.md

# 3) Single reviewed commit folding the bump + docs pass.
echo "==> Committing release ${VERSION}"
git add pom.xml CHANGELOG.md README.md
git commit -m "chore(release): ${VERSION}"

# 4) Annotated tag.
echo "==> Tagging ${TAG}"
git tag -a "${TAG}" -m "Release ${VERSION}"

# 5) STOP before the push — the point of no return is the operator's deliberate act.
cat <<EOF

──────────────────────────────────────────────────────────────────────────────
  Release ${VERSION} is committed and tagged locally — NOTHING has been pushed.

  Review the commit and tag now:
      git show --stat HEAD
      git show ${TAG}

  When you are satisfied, push to trigger publication (POINT OF NO RETURN — D-03):
      git push origin main --follow-tags

  Because autoPublish=true, a validation-passing bundle goes live on the immutable
  Central coordinate as soon as CI runs on the pushed tag. There is no undo.
──────────────────────────────────────────────────────────────────────────────
EOF
