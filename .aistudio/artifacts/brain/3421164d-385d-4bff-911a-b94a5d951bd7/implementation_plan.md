# PotrikaGhor (পত্রিকাঘর) — Complete Reader, Coverage, & Personalization Suite

This plan delivers the complete set of reader experience, smart coverage, data-saving, and Play Store readiness features for **PotrikaGhor (পত্রিকাঘর)** while preserving the existing Bengali editorial aesthetic, AdMob Native Ads setup, and local-first privacy architecture.

## User Review & Critical Decisions

> [!IMPORTANT]
> All core product choices were confirmed during Phase 1 clarification and are incorporated into this plan without modifying existing app identity, signing, or AdMob credentials.

- **Confirmed Decision 1 (Full Feature Scope)**: Implement all remaining items from the 4-part roadmap (`১.ক–১.গ`, `২.ক–২.গ`, `৩.ক–৩.খ`, `৪.ক–৪.গ`) in a cohesive update.
- **Confirmed Decision 2 (Local News Filter — ১.ক)**: Provide quick-select filter chips for all **8 Divisions** (`ঢাকা`, `চট্টগ্রাম`, `রাজশাহী`, `খুলনা`, `বরিশাল`, `সিলেট`, `রংপুর`, `ময়মনসিংহ`) alongside major districts (`কুমিল্লা`, `গাজীপুর`, `নারায়ণগঞ্জ`, `বগুড়া`, `কক্সবাজার`, `যশোর`, `সিলেট`, `ফরিদপুর`, `দিনাজপুর`, `নোয়াখালী`, `পাবনা`, `টাঙ্গাইল`) when the Local News category is selected.
- **Confirmed Decision 3 (Photo Share Card Themes — ১.খ)**: Add **3 selectable share card themes** (`ক্লাসিক পেপার`, `ডার্ক এডিটোরিয়াল`, and `রেড ব্রেকিং`) in both the live Compose preview and the exported 1080×760 PNG bitmap.

---

## 1. Overview & Core Concept

- **What It Does**: Elevates PotrikaGhor with division/district local news filtering, multi-theme social photo quote cards, a dedicated reading history view alongside read-article dimming, a top multi-paper coverage preview on the Home feed with direct jump to the Coverage tab, custom keyword muting, a dedicated e-Paper newspaper tab, a mobile Data Saver mode, and one-tap Play Store rating and app-sharing actions.
- **Target Audience / Persona**: Bengali news readers who want fast, clutter-free access to national, regional, and e-Paper news on Android with strong customization and low mobile data consumption.
- **Key Value**: Gives readers complete control over *what* they read (local divisions, muted topics, e-Papers), *how* they read and share (Clean Reader mode, 3 share card styles, reading history), and *how much data* the app uses (instant cached startup + Data Saver mode).

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Division & District Local News (`১.ক`)**: Selecting the `"স্থানীয়"` (Local News) chip on the Home screen reveals a secondary horizontal chip bar containing Bangladesh's 8 divisions and major districts. Tapping any division/district immediately fetches and caches regional headlines for that area.
  2. **3-Theme Photo Share Card (`১.খ`)**: Tapping the Share icon in the Reader opens the Share Card dialog with a 3-chip selector (`ক্লাসিক পেপার`, `ডার্ক এডিটোরিয়াল`, `রেড ব্রেকিং`). Both the on-screen preview card and the generated PNG image switch palettes and masthead styling in real time.
  3. **Read Dimming & Reading History (`১.গ`)**: Opened articles are marked with `"• পঠিত"` and 62% headline opacity in the feed, and are recorded in a local Reading History list (`সাম্প্রতিক পঠন`) accessible as a quick toggle inside the Bookmarks screen so users can reopen any recently read story.
  4. **Top Coverage Preview on Home (`২.ক`)**: On the Home feed, when multi-source stories (covered by 3+ newspapers) are present, a compact `"বহু-পত্রিকা শীর্ষ কভারেজ"` card highlights the top 2 multi-source stories and offers a `"সব কভারেজ দেখুন →"` button that switches directly to the Coverage tab.
  5. **Keyword Mute Filter (`২.খ`)**: In Settings, users can add or remove muted words/topics (e.g., specific keywords they wish to avoid). Any headline matching a muted keyword is automatically filtered out of the Home and Coverage feeds.
  6. **e-Paper Quick Filter (`২.গ`)**: In the My Papers (`আমার পত্রিকা`) screen, a new `"ই-পেপার"` category chip displays curated direct e-Paper editions of major Bengali dailies (`প্রথম আলো ই-পেপার`, `ইত্তেফাক ই-পেপার`, `কালের কণ্ঠ ই-পেপার`, `যুগান্তর ই-পেপার`, `বাংলাদেশ প্রতিদিন ই-পেপার`, `সমকাল ই-পেপার`, `বণিক বার্তা ই-পেপার`, `কালবেলা ই-পেপার`, `নয়া দিগন্ত ই-পেপার`, `মানবজমিন ই-পেপার`).
  7. **Instant Cached Startup & Data Saver Mode (`৩.ক` & `৩.খ`)**: A `"ডেটা সেভার মোড"` switch in Settings disables automatic article thumbnail downloading on mobile data (showing clean newspaper avatars instead) while preserving instant cached feed startup.
  8. **Ad Visibility & Play Store Actions (`৪.ক`, `৪.খ`, `৪.গ`)**: The existing `hideAds` state is respected by the Home feed ad slot renderer, and the Settings About card includes `"অ্যাপটি শেয়ার করুন"` and `"Play Store-এ রেটিং দিন"` buttons.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Warm Bengali Editorial Material 3 design (`FontFamily.Serif` headlines, crisp metadata badges, and generous card padding).
  - *Share Card Palettes*:
    - **Classic Paper (`ক্লাসিক পেপার`)**: Warm newsprint `#F3EEE4` background, `#16130F` ink text, `#D4382C` crimson masthead.
    - **Dark Editorial (`ডার্ক এডিটোরিয়াল`)**: Deep `#121418` background, `#F5F2EB` headline text, `#FF6E63` coral-red accent border and masthead.
    - **Red Breaking (`রেড ব্রেকিং`)**: Bold `#B71C1C` / `#D4382C` crimson header band with white `"ব্রেকিং ও শীর্ষ সংবাদ"` masthead and high-contrast `#FFFDF9` card body.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Local-First Persistence for History, Muted Keywords, and Feed Cache**
  - *Chosen Approach*: Store `readHistory`, `mutedKeywords`, `dataSaverMode`, and per-category cached feed items inside the existing local state repository (`SharedPreferences` + `kotlinx.serialization`).
  - *Why*: Preserves PotrikaGhor's zero-account, 100% private local-data guarantee with zero external database latency.
- **Decision 2: Non-Destructive Keyword Filtering**
  - *Chosen Approach*: Apply `mutedKeywords` filtering at the UI/presentation derivation layer rather than discarding raw RSS cache entries.
  - *Why*: Removing a muted keyword in Settings immediately restores matching stories without requiring a network re-fetch.
- **Decision 3: Data Saver Image Strategy**
  - *Chosen Approach*: When `dataSaverMode` is enabled (or when `offlineMode == "manual"` on metered networks), `ArticleCoverImage` skips background HTML/OG image scraping and renders the lightweight newspaper avatar placeholder unless an image is already cached in memory.
  - *Why*: Cuts mobile data usage and background HTTP requests dramatically on slow cellular connections.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```text
┌──────────────────────────────────────────────────────────────────────────┐
│                     AppStateRepository (Local State)                     │
│  • readArticles & readHistory (List<ReadHistoryItem>, max 100)           │
│  • mutedKeywords (List<String>) & dataSaverMode (Boolean)                │
│  • preferCleanReader (Boolean) & cachedFeeds (per-category JSON)         │
└───────────────┬──────────────────────────────┬───────────────────────────┘
                │                              │
                ▼                              ▼
┌───────────────────────────────────┐  ┌───────────────────────────────────┐
│     MainActivity (Nav Shell)      │  │     NewsRepository (RSS/Cache)    │
│  • Tab 0: আমার নিউজ (Home)        │  │  • Direct + Google News RSS       │
│  • Tab 1: কভারেজ (Coverage)       │  │  • Division/District Local Query  │
│  • Tab 2: আমার পত্রিকা (+e-Paper) │  │  • In-memory Category Cache       │
│  • Tab 3: বুকমার্ক (+পঠন ইতিহাস)  │  └───────────────┬───────────────────┘
│  • Tab 4: সেটিংস (Mute/DataSaver) │                  │
└───────────────┬───────────────────┘                  │
                │                                      │
                ▼                                      ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                   UI Screens & Interactive Components                    │
│  ┌────────────────────────────┐    ┌──────────────────────────────────┐  │
│  │ PersonalHomeScreen         │    │ ReaderScreen & ShareCardDialog   │  │
│  │ • 8-Division Local Chips   │    │ • [ক্লিন রিডার | মূল ওয়েবসাইট]   │  │
│  │ • Top Coverage Preview Card│    │ • 3-Theme Canvas PNG Generator   │  │
│  │ • Read Dimming & Mute Pass │    │ • Auto Read History Recording    │  │
│  └────────────────────────────┘    └──────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State

- **`ReadHistoryItem` (`@Serializable`)**:
  - Fields: `url: String`, `title: String`, `sourceName: String`, `paperUrl: String`, `readAt: Long`.
  - Stored in `AppSettings.readHistory` (capped at the 100 most recent articles).
- **`ShareCardTheme` Enum**:
  - Values: `CLASSIC_PAPER` (`"ক্লাসিক পেপার"`), `DARK_EDITORIAL` (`"ডার্ক এডিটোরিয়াল"`), `RED_BREAKING` (`"রেড ব্রেকিং"`).
- **Local News Regions (`LocalRegions`)**:
  - 8 Divisions (`ঢাকা`, `চট্টগ্রাম`, `রাজশাহী`, `খুলনা`, `বরিশাল`, `সিলেট`, `রংপুর`, `ময়মনসিংহ`) + 12 major districts (`কুমিল্লা`, `গাজীপুর`, `নারায়ণগঞ্জ`, `বগুড়া`, `কক্সবাজার`, `যশোর`, `ফরিদপুর`, `দিনাজপুর`, `নোয়াখালী`, `পাবনা`, `টাঙ্গাইল`, `কুষ্টিয়া`).
  - Default `"local"` category added to `DefaultHomeCategories` (and automatically merged into existing saved home categories if absent).
- **e-Paper Entries**:
  - Category `Category("epaper", "ই-পেপার")` added to `PapersData.categories` along with 10 curated e-Paper `Paper` definitions.

### Interactive Component & State Mapping

- **Home Feed (`PersonalHomeScreen`)**:
  - Selecting the `"স্থানীয়"` category chip displays the secondary Division/District `LazyRow`; tapping any region chip updates `selectedLocalRegion` and triggers `loadData(force = true)` with that region's query.
  - Filters `newsItems` and `coverageList` through `appState.mutedKeywords` (case-insensitive substring match on title/snippet).
  - Renders the `"বহু-পত্রিকা শীর্ষ কভারেজ"` preview card when `multiSourceStories` (`count >= 3`) is non-empty, invoking `onOpenCoverage()` (`selectedTab = 1`) when `"সব কভারেজ দেখুন →"` is tapped.
  - Checks `!appState.hideAds && AdMobConfig.shouldShowAdAfterFeedIndex(...)` before rendering native ad slots.
- **Bookmarks & Reading History (`BookmarksScreen`)**:
  - Top segmented toggle switches between `"বুকমার্ক তালিকা"` and `"সাম্প্রতিক পঠন (ইতিহাস)"`, allowing one-tap reopening of any recently read article or clearing reading history.
- **Settings (`SettingsScreen`)**:
  - Adds **Data Saver Mode (`ডেটা সেভার মোড`)** toggle and **Ad Display (`ফিডে বিজ্ঞাপন লুকান`)** toggle.
  - Adds **Keyword Mute (`কি-ওয়ার্ড মিউট ফিল্টার`)** section with text input + add button and removable `InputChip`/`FilterChip` tags for active muted words.
  - Adds **Share App (`অ্যাপটি শেয়ার করুন`)** and **Rate on Play Store (`Play Store-এ রেটিং দিন`)** buttons in the About card.
