#!/usr/bin/env bash
# Release-time compatibility check against HPE's published Morpheus plugin sources (read-only).
#
#   tools/check-plugin-core.sh                    # report changes since compat/plugin-core-baseline.properties
#   tools/check-plugin-core.sh --update-baseline  # after reviewing, record today's state as the new baseline
#
# Reports:
#   - the latest morpheus-plugin-api on Maven Central (a new version = check it before claiming support);
#   - new rel-* tags in HewlettPackard/morpheus-plugin-core (informational; rel-2.10.0 is a stray 2025 tag);
#   - commits since the baseline that touch the API classes this plugin uses, the plugin loader/dispatcher,
#     the renderer, ReleaseNotes.adoc or HTTPRouting.adoc;
#   - HewlettPackard/morpheus-docs: new dev-* branches, the |pluginVer| value and every "Plugin API" line in
#     release_notes/compatibility.rst.
# Exit 0 = nothing changed, 1 = review needed, 2 = error. Uses only public sources; reads source, never jars.
set -uo pipefail
cd "$(dirname "$0")/.."
BASE=compat/plugin-core-baseline.properties
prop() { grep -E "^$1=" "$BASE" | cut -d= -f2-; }
WORK="${TMPDIR:-/tmp}/com-morpheus-compat"; mkdir -p "$WORK"
CORE="$WORK/plugin-core"; DOCS="$WORK/morpheus-docs"
changed=0
note() { echo "  ! $*"; changed=1; }

echo "== Maven Central: com.morpheusdata:morpheus-plugin-api"
latest=$(curl -fsS https://repo1.maven.org/maven2/com/morpheusdata/morpheus-plugin-api/maven-metadata.xml | grep -oE '<release>[^<]+' | cut -d'>' -f2) || { echo "cannot reach Maven Central"; exit 2; }
echo "  latest $latest (baseline $(prop mavenLatest), built against $(prop buildApiVersion))"
[ "$latest" != "$(prop mavenLatest)" ] && note "new Plugin API release $latest: run the tests against it (-PtestApiVersion=$latest) and check the release notes"

echo "== HewlettPackard/morpheus-plugin-core"
if [ -d "$CORE/.git" ]; then git -C "$CORE" fetch -q --tags origin 2>/dev/null; else git clone -q --filter=blob:none https://github.com/HewlettPackard/morpheus-plugin-core.git "$CORE" || exit 2; fi
branch=$(git -C "$CORE" remote show origin 2>/dev/null | sed -n 's/.*HEAD branch: //p'); branch=${branch:-$(prop pluginCoreBranch)}
git -C "$CORE" fetch -q origin "$branch" 2>/dev/null
head=$(git -C "$CORE" rev-parse --short "origin/$branch")
echo "  default branch $branch at $head (baseline $(prop pluginCoreBranch) at $(prop pluginCoreCommit))"
[ "$branch" != "$(prop pluginCoreBranch)" ] && note "default branch moved to $branch (a new API line?)"
echo "  rel-* tags: $(git -C "$CORE" tag -l 'rel-*' | sort -V | tail -4 | tr '\n' ' ')"
API=morpheus-plugin-api/src/main/java/com/morpheusdata
WATCH=(
  $API/core/Plugin.java $API/core/PluginManager.java $API/core/ChildFirstClassLoader.java $API/core/MorpheusContext.java
  $API/core/MorpheusServices.java $API/core/AbstractServerTabProvider.java $API/core/AbstractGlobalUIComponentProvider.java
  $API/core/synchronous/compute/MorpheusSynchronousComputeServerService.java $API/core/MorpheusSynchronousDataService.java
  $API/model/Account.java $API/model/ComputeServer.java $API/model/ComputeServerInterface.java $API/model/ComputeServerType.java
  $API/model/MetadataTag.java $API/model/OptionType.java $API/model/Permission.java $API/model/User.java
  $API/views/HandlebarsRenderer.java $API/views/DynamicTemplateLoader.java $API/views/HTMLResponse.java $API/views/JsonResponse.java $API/views/ViewModel.java
  $API/web/PluginController.java $API/web/Route.java $API/web/Dispatcher.java
  morpheus-plugin-docs/src/docs/asciidoc/ReleaseNotes.adoc morpheus-plugin-docs/src/docs/asciidoc/HTTPRouting.adoc
)
base=$(prop pluginCoreCommit)
if git -C "$CORE" cat-file -e "$base" 2>/dev/null; then
  diffs=$(git -C "$CORE" log --oneline "$base..origin/$branch" -- "${WATCH[@]}")
  if [ -n "$diffs" ]; then note "commits since $base touching watched files:"; echo "$diffs" | sed 's/^/      /'
    git -C "$CORE" diff --stat "$base" "origin/$branch" -- "${WATCH[@]}" | sed 's/^/      /'
  else echo "  no changes to watched files since $base"; fi
else note "baseline commit $base not found on $branch (history rewritten or new branch): review manually"; fi

echo "== HewlettPackard/morpheus-docs"
if [ -d "$DOCS/.git" ]; then git -C "$DOCS" fetch -q origin 2>/dev/null; else git clone -q --filter=blob:none --no-checkout https://github.com/HewlettPackard/morpheus-docs.git "$DOCS" || exit 2; fi
branches=$(git -C "$DOCS" ls-remote --heads origin 'dev-*' | sed 's#.*refs/heads/##' | sort -V | tr '\n' ' ' | sed 's/ $//')
echo "  dev branches: $branches (baseline $(prop docsBranches))"
[ "$branches" != "$(prop docsBranches)" ] && note "new docs branch(es): a new Morpheus release line to check"
newest=$(echo "$branches" | tr ' ' '\n' | tail -1)
git -C "$DOCS" fetch -q origin "$newest" 2>/dev/null
pv=$(git -C "$DOCS" show "origin/$newest:conf.py" 2>/dev/null | sed -n 's/.*|pluginVer| replace:: *//p')
echo "  $newest |pluginVer| = $pv (baseline $(prop docsPluginVer))"
[ "$pv" != "$(prop docsPluginVer)" ] && note "docs now state Plugin API $pv"
echo "  compatibility.rst Plugin API lines:"
git -C "$DOCS" show "origin/$newest:release_notes/compatibility.rst" 2>/dev/null | grep -iE 'plugin api [0-9]|Plugin API updated' | sed 's/^/      /'
dhead=$(git -C "$DOCS" rev-parse --short "origin/$newest")

if [ "${1:-}" = "--update-baseline" ]; then
  buildApi=$(prop buildApiVersion)   # read before the file is rewritten
  cat > "$BASE" <<EOB
# What tools/check-plugin-core.sh last reviewed. Update with --update-baseline after reviewing a report.
# The plugin is built against the oldest supported API and must run on every newer one listed here.
checkedOn=$(date +%F)
buildApiVersion=${buildApi}
mavenLatest=$latest
pluginCoreBranch=$branch
pluginCoreCommit=$head
docsBranches=$branches
docsCommit=$dhead
docsPluginVer=$pv
EOB
  echo "== baseline updated"; exit 0
fi
if [ $changed -eq 0 ]; then echo "== OK: no compatibility-relevant changes since $(prop checkedOn)"; else echo "== REVIEW NEEDED (see ! lines), then rerun with --update-baseline"; fi
exit $changed
