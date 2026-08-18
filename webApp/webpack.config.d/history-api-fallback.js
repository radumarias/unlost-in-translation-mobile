// The pairing deep link is `<origin>/j#…` (issue #33): a path this single-page app
// has no file for. Without a rewrite the dev server answers 404, the wasm module
// never boots, and a scanned QR opens a blank error page instead of a conversation.
//
// This is the dev-server half. A deployed web build needs the same rule from its
// host — `_redirects` on Cloudflare Pages, `try_files` on nginx — which is why one
// is committed next to index.html.
//
// The Kotlin Gradle plugin's devServer DSL exposes port/proxy/static and nothing
// else, so this goes through `webpack.config.d`, which the plugin appends to the
// generated config verbatim.
config.devServer = config.devServer || {};
config.devServer.historyApiFallback = true;
