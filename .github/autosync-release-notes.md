This stable release brings AutoSync to parity with the beta builds. Only AutoSync changed; the NuvioTV 1.1.0 beta features are still not included.

- **Auto Sync Tolerance setting.** In the Playback settings, under Subtitles, choose a tolerance (100–500 ms). When a confident match would move the subtitle by no more than that, its original timing is kept.
- **AutoSync toasts follow the app language.** All AutoSync messages (analyzing, match found, sync applied or kept, errors) now use the language picked in Nuvio TV's settings, with translations for all 35 languages the app ships. English wording is unchanged.
- **Garbled accented characters fixed in the subtitle timing screen.** Nuvio TV's own subtitle parser is upstream's again, which brings back its cleanup of broken characters. AutoSync keeps its own reader for SRT, WebVTT, ASS/SSA and TTML.
- **One download for the selected subtitle.** The on-screen subtitles and AutoSync now share it, and a failed download can no longer leave either one waiting.
- **AutoSync plugs into Nuvio TV through a few marked hooks,** the same structure as the beta builds, so both stay easy to update. Syncing works the same as before.
