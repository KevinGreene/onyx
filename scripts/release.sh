#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

if [[ "$#" -ne 4 ]]; then
    echo "Usage: $0 old-version new-version old-release-branch new-release-branch"
    echo "Example: $0 0.7.1 0.8.0 0.7.x 0.8.x"
else
  # Update to release version.
  git checkout master
  git pull --rebase

  OLD_VERSION=$1
  NEW_VERSION=$2
  OLD_BRANCH=$3
  NEW_BRANCH=$4

  grep "$OLD_VERSION" README.MD || (echo "Version string $1 was not found in README" && exit 1)

  lein set-version $NEW_VERSION
  sed -i '' "s/$OLD_VERSION/$NEW_VERSION/g" README.md
  sed -i '' "s/$3/$4/g" README.md
  sed -i '' "s/$3/$4/g" circle.yml
  git rm -rf doc/api
  lein doc

  # Push and deploy release.
  git add .
  git commit -m "Release version $NEW_VERSION."
  git tag $NEW_VERSION
  git push origin $NEW_VERSION
  git push origin master

  # Merge artifacts into release branch.
  git checkout $NEW_BRANCH
  git merge --no-edit master
  git push origin $NEW_BRANCH

  # Prepare next release cycle.
  git checkout master
  lein set-version
  git add .
  git commit -m "Prepare for next release cycle."
  git push origin master
fi
