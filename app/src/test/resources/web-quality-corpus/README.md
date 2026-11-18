# Synthetic web quality corpus

All 72 HTML pages in this corpus are locally generated synthetic fixtures. They contain no captured third-party page text, credentials, or paywalled material.

- 48 extraction cases across 8 structural families: schema articleBody, semantic article, main, article-content div, misspelled article id with br lines, dialogue, table rows, and a nested shell with a teaser link.
- 16 typed refusal cases: login, paywall, antibot, and empty content (4 each).
- 8 Litmir-shaped pagination contract cases: 4 same-book next links and 4 next-book links that must be rejected.
- 40 extraction cases are the fit split; 8 extraction cases are held out. All refusal and pager cases are held out.
- Real/public-domain pages: 0. This synthetic corpus checks contracts and common structural variants; it does not establish real-world site coverage or close the representative-corpus blocker by itself.

Run with `:app:testDebugUnitTest --tests com.ozvuchka.app.importer.WebQualityCorpusTest`.
