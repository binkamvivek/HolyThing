# HolyThing - Content Backend (Phase 1)

This folder contains the complete Google Apps Script backend for the **HolyThing** Android Bible app. The backend connects directly to your content Google Sheet (~850 rows), fills empty verses via the public Bible API, validates content integrity, and exposes a high-performance, cached JSON web endpoint with incremental sync support (`?since=<version>`).

---

## Files in `/backend`

- [Code.gs](file:///c:/Users/dheer/OneDrive/Desktop/HolyThing/backend/Code.gs): Google Apps Script logic (`fillVerseText`, `validateSheet`, `doGet`, cache chunking, menu).
- [appsscript.json](file:///c:/Users/dheer/OneDrive/Desktop/HolyThing/backend/appsscript.json): Apps Script project manifest with web app configuration and OAuth scopes.
- [schema.json](file:///c:/Users/dheer/OneDrive/Desktop/HolyThing/backend/schema.json): Formal JSON Schema specification for the Android sync client (Room/DataStore ingestion).
- [README.md](file:///c:/Users/dheer/OneDrive/Desktop/HolyThing/backend/README.md): Setup, deployment, triggers, and manual verification guide.

---

## 1. Sheet Setup & Column Structure

Ensure the first sheet (or a sheet named `Cards`) contains the following 16 column headers in row 1:

| Column Header | Type | Description / Accepted Values |
|---|---|---|
| `id` | string | Unique card identifier (e.g. `card_001`, `romans_8_28`) |
| `format` | string | `verse`, `story`, `testimony`, `didyouknow`, `question` |
| `hook` | string | Attention line shown first in bold/large typography |
| `verse_ref` | string | Scripture reference (e.g. `Psalm 23:1-3`, `John 3:16`) |
| `verse_text` | string | Full Scripture text (populated automatically by `fillVerseText`) |
| `translation` | string | `WEB` (default), `KJV`, `BSB`, etc. |
| `insight` | string | Plain-language context / reflection |
| `topics` | string | Comma-separated topics (e.g. `grace, salvation, peace`) |
| `moods` | string | Comma-separated moods (e.g. `anxious, tired, hopeful, lost`) |
| `tone` | string | Tone descriptor (e.g. `comforting`, `inspiring`, `historical`) |
| `book` | string | Book of the Bible (e.g. `Psalms`, `Romans`) |
| `length` | string | `short`, `medium`, `long` |
| `series_id` | string | Narrative series ID (required for `story` format, empty otherwise) |
| `series_order` | number | Order in series starting from 1 (required for `story` format) |
| `reflection_prompt` | string | Gentle prompt inviting prayer, breath, or reflection |
| `active` | boolean | `TRUE` or `FALSE` (only `TRUE` rows are served to users) |

---

## 2. Installing in Google Apps Script

1. Open your Google Sheet in a web browser.
2. In the top menu, click **Extensions** > **Apps Script**.
3. In the Apps Script editor:
   - Click **Project Settings** (gear icon on the left navigation).
   - Check the box **"Show 'appsscript.json' manifest file in editor"**.
   - Return to the **Editor** (< > icon).
   - Open `appsscript.json` and paste the contents of [appsscript.json](file:///c:/Users/dheer/OneDrive/Desktop/HolyThing/backend/appsscript.json).
   - Open `Code.gs` (or rename default script) and replace its contents with [Code.gs](file:///c:/Users/dheer/OneDrive/Desktop/HolyThing/backend/Code.gs).
4. Click the **Save** icon (diskette) or press `Ctrl + S`.
5. Reload your Google Sheet. You will see a new menu item: **HolyThing**.

---

## 3. Operations & Menu Functions

### Step 3.1: Fill Verse Text (`HolyThing > 1. Fill Verse Text`)
- Scans every row with an empty `verse_text`.
- Calls `https://bible-api.com/{reference}?translation={translation}` (defaults to `web` if empty or unsupported).
- Strips unwanted linebreaks and extra spaces.
- **Rate-limit safe:** Pauses 150ms between requests.
- **Apps Script Timeout Safe:** If reaching 4.5 minutes of execution time, it pauses safely, saves its row position in `PropertiesService`, and notifies you. Simply click **1. Fill Verse Text** again to resume.
- **Error Handling:** Any verse that cannot be found or fails HTTP status 200 is logged to a newly created **"Errors"** tab with timestamp, row, ID, reference, and error message.

### Step 3.2: Validate Sheet (`HolyThing > 2. Validate Sheet`)
- Verifies:
  - Required fields are non-empty (`id`, `format`, `hook`, `verse_ref`, `verse_text`, `active`).
  - `id` uniqueness (flags duplicate rows with red cell highlight).
  - Format is one of the 5 allowed formats.
  - Story cards have valid `series_id` and positive `series_order`.
  - `active` is boolean `TRUE` or `FALSE`.
  - Flags inconsistent casing, trailing whitespace, or special characters in `moods` and `topics`.
- Highlights problematic cells directly on the sheet (Red = Error, Yellow = Warning).
- Creates/refreshes a **"Validation"** sheet:
  - Detailed issue list with row numbers and exact problem.
  - **Distinct Moods Table**: complete list of unique moods and their frequencies (for Android onboarding chips).
  - **Distinct Topics Table**: complete list of unique topics and their frequencies.

### Step 3.3: Preview JSON (`HolyThing > 3. Preview JSON / Endpoint Info`)
- Opens an in-sheet dialog displaying the total active cards count, current data SHA-256 version hash, updatedAt timestamp, and sample JSON payload.

---

## 4. Deploying the Web App Endpoint (`doGet`)

To provide the content feed to the Android app:

1. In the Apps Script editor, click the blue **Deploy** button (top right) > **New deployment**.
2. Click the gear icon next to "Select type" and choose **Web app**.
3. Configure:
   - **Description**: `HolyThing Content API v1`
   - **Execute as**: `Me (<your_email>@gmail.com)`
   - **Who has access**: `Anyone` *(Note: this allows the Android app to fetch content without user Google authentication)*.
4. Click **Deploy**.
5. Grant permissions when prompted by Google (click *Advanced* > *Go to HolyThing (unsafe)* > *Allow*).
6. Copy the **Web App URL** provided (ends with `/exec`).

> [!IMPORTANT]
> Whenever you modify `Code.gs` in the future, you must go to **Deploy** > **Manage deployments** > **Edit** > select **New version** > **Deploy** for changes to take effect on the public endpoint.

---

## 5. Setting Up Triggers for Cache Invalidation

To ensure edits in the sheet immediately invalidate the cached API output:

1. In the Apps Script left sidebar, click **Triggers** (alarm clock icon).
2. Click **Add Trigger** (bottom right).
3. Set the following options:
   - **Choose which function to run**: `onEdit`
   - **Choose which deployment should run**: `Head`
   - **Select event source**: `From spreadsheet`
   - **Select event type**: `On edit`
4. Click **Save**.

The script also provides a manual **HolyThing > Clear Cache & Invalidate Version** menu option whenever you wish to force re-indexing manually.

---

## 6. API Endpoint Specification

### Full Sync (Initial or after changes)
- **Request**: `GET https://script.google.com/macros/s/<DEPLOYMENT_ID>/exec`
- **Response**:
```json
{
  "version": "7a3f89b1c2e409da",
  "updatedAt": "2026-11-22T17:15:00.000Z",
  "count": 850,
  "cards": [
    {
      "id": "card_001",
      "format": "verse",
      "hook": "When anxiety overwhelms your thoughts...",
      "verse_ref": "Psalm 94:19",
      "verse_text": "In the multitude of my thoughts within me, your comforts delight my soul.",
      "translation": "WEB",
      "insight": "The psalmist doesn't deny having anxious thoughts—he acknowledges God's presence in the middle of them.",
      "topics": ["peace", "anxiety", "comfort"],
      "moods": ["anxious", "overwhelmed", "tired"],
      "tone": "comforting",
      "book": "Psalms",
      "length": "short",
      "series_id": null,
      "series_order": null,
      "reflection_prompt": "Take a slow breath. Which heavy thought can you hand over right now?"
    }
  ]
}
```

### Incremental Sync Check (When client already has data)
- **Request**: `GET https://script.google.com/macros/s/<DEPLOYMENT_ID>/exec?since=7a3f89b1c2e409da`
- **Response (Nothing changed)**:
```json
{
  "upToDate": true,
  "version": "7a3f89b1c2e409da"
}
```
If the backend has changed (different hash), the endpoint automatically returns the full payload with the new `version`.

