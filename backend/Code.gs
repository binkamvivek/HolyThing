/**
 * HolyThing - Content Backend
 * Google Apps Script bound to the HolyThing Content Spreadsheet.
 *
 * Provides:
 * 1. fillVerseText() - Fetches verse text from bible-api.com for empty rows with rate limiting and resume capability.
 * 2. validateSheet() - Comprehensive data validation, error/warning highlighting, and mood/topic discovery.
 * 3. doGet(e) - Fast JSON Web App endpoint with version hashing, caching, and ?since=<version> incremental check.
 * 4. Menu & Triggers - Custom spreadsheet menu and onEdit cache invalidation.
 */

// ============================================================================
// CONSTANTS & CONFIGURATION
// ============================================================================

const CONFIG = {
  DATA_SHEET_NAME: 'Cards', // Fallback to first sheet if name doesn't match
  VALIDATION_SHEET_NAME: 'Validation',
  ERRORS_SHEET_NAME: 'Errors',
  
  // Rate limiting & execution limits
  API_DELAY_MS: 150, // Utilities.sleep between calls to respect bible-api.com
  MAX_EXECUTION_TIME_MS: 4.5 * 60 * 1000, // 4.5 minutes (Apps Script timeout is 6 mins)
  CHUNK_CACHE_SIZE: 90000, // Apps Script cache limit is 100KB per key
  CACHE_EXPIRATION_SEC: 21600, // 6 hours
  
  // Expected columns in sheet
  COLUMNS: [
    'id',
    'format',
    'hook',
    'verse_ref',
    'verse_text',
    'translation',
    'insight',
    'topics',
    'moods',
    'tone',
    'book',
    'length',
    'series_id',
    'series_order',
    'reflection_prompt',
    'active'
  ],
  
  // Enums for validation
  VALID_FORMATS: ['verse', 'story', 'testimony', 'didyouknow', 'question'],
  VALID_LENGTHS: ['short', 'medium', 'long'],
  DEFAULT_TRANSLATION: 'web',
  
  // Script Property Keys
  PROP_LAST_ROW: 'HOLYTHING_LAST_PROCESSED_ROW',
  PROP_VERSION: 'HOLYTHING_DATA_VERSION',
  PROP_UPDATED_AT: 'HOLYTHING_LAST_UPDATED_AT',
  CACHE_KEY_PAYLOAD: 'HOLYTHING_CARDS_PAYLOAD'
};

// ============================================================================
// MENU & UI SETUP
// ============================================================================

/**
 * Creates custom HolyThing menu on spreadsheet open.
 */
function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu('HolyThing')
    .addItem('1. Fill Verse Text', 'fillVerseText')
    .addItem('2. Validate Sheet', 'validateSheet')
    .addItem('3. Preview JSON / Endpoint Info', 'previewJson')
    .addSeparator()
    .addItem('Clear Cache & Invalidate Version', 'clearCacheAndReindex')
    .addItem('Reset Fill Progress Tracker', 'resetFillProgress')
    .addToUi();
}

/**
 * Simple onEdit trigger to invalidate cache when edits occur.
 * For production, an installable onEdit trigger is recommended.
 */
function onEdit(e) {
  try {
    invalidateCache();
  } catch (err) {
    Logger.log('onEdit cache invalidation warning: ' + err.message);
  }
}

// ============================================================================
// 1. FILL VERSE TEXT (fillVerseText)
// ============================================================================

/**
 * Scans for rows where `verse_text` is empty, fetches the Scripture from
 * bible-api.com, cleans up formatting, and writes it back.
 * Handles ranges, URL encoding, rate limiting, and 6-min execution limits safely.
 */
function fillVerseText() {
  const startTime = Date.now();
  const sheet = getDataSheet();
  const data = sheet.getDataRange().getValues();
  if (data.length < 2) {
    SpreadsheetApp.getActiveSpreadsheet().toast('Sheet has no data rows.', 'HolyThing');
    return;
  }
  
  const headers = data[0].map(h => String(h).trim().toLowerCase());
  const headerMap = getHeaderMap(headers);
  
  // Verify required columns exist
  if (headerMap.verse_ref === undefined || headerMap.verse_text === undefined) {
    SpreadsheetApp.getUi().alert('Error: sheet must have "verse_ref" and "verse_text" columns.');
    return;
  }
  
  const scriptProps = PropertiesService.getScriptProperties();
  const savedRow = parseInt(scriptProps.getProperty(CONFIG.PROP_LAST_ROW) || '2', 10);
  const startRow = (savedRow >= 2 && savedRow <= data.length) ? savedRow : 2;
  
  let filledCount = 0;
  let skippedCount = 0;
  let errorCount = 0;
  let stoppedEarly = false;
  let lastProcessedRow = startRow;
  
  const errorsSheet = getOrCreateSheet(CONFIG.ERRORS_SHEET_NAME, [
    'Timestamp', 'Row', 'Card ID', 'Verse Ref', 'Translation', 'Error Message'
  ]);
  
  for (let r = startRow; r <= data.length; r++) {
    // Time limit safety check
    if (Date.now() - startTime > CONFIG.MAX_EXECUTION_TIME_MS) {
      stoppedEarly = true;
      lastProcessedRow = r;
      break;
    }
    
    const rowValues = data[r - 1];
    const id = headerMap.id !== undefined ? String(rowValues[headerMap.id]).trim() : `Row ${r}`;
    const verseRef = String(rowValues[headerMap.verse_ref] || '').trim();
    const existingText = String(rowValues[headerMap.verse_text] || '').trim();
    const rawTranslation = headerMap.translation !== undefined ? String(rowValues[headerMap.translation] || '').trim() : '';
    const translation = rawTranslation.toLowerCase() || CONFIG.DEFAULT_TRANSLATION;
    
    // Skip if verse_text is already present
    if (existingText.length > 0) {
      skippedCount++;
      lastProcessedRow = r;
      continue;
    }
    
    // If verse_ref is empty, we cannot fetch
    if (!verseRef) {
      skippedCount++;
      lastProcessedRow = r;
      continue;
    }
    
    // Fetch verse from API
    const fetchResult = fetchVerseFromApi(verseRef, translation);
    
    if (fetchResult.success) {
      sheet.getRange(r, headerMap.verse_text + 1).setValue(fetchResult.text);
      filledCount++;
    } else {
      errorCount++;
      errorsSheet.appendRow([
        new Date().toISOString(),
        r,
        id,
        verseRef,
        translation,
        fetchResult.error
      ]);
    }
    
    lastProcessedRow = r;
    Utilities.sleep(CONFIG.API_DELAY_MS);
  }
  
  // Update progress tracker
  if (stoppedEarly && lastProcessedRow < data.length) {
    scriptProps.setProperty(CONFIG.PROP_LAST_ROW, String(lastProcessedRow));
    const msg = `Paused safely near execution limit at row ${lastProcessedRow}/${data.length}.\nFilled: ${filledCount}, Errors: ${errorCount}.\nRun "Fill Verse Text" again to resume.`;
    SpreadsheetApp.getActiveSpreadsheet().toast(msg, 'HolyThing: In Progress', 10);
    SpreadsheetApp.getUi().alert(msg);
  } else {
    scriptProps.deleteProperty(CONFIG.PROP_LAST_ROW);
    invalidateCache();
    const msg = `Completed!\nFilled: ${filledCount}\nSkipped: ${skippedCount}\nErrors: ${errorCount} (see "${CONFIG.ERRORS_SHEET_NAME}" sheet)`;
    SpreadsheetApp.getActiveSpreadsheet().toast(msg, 'HolyThing: Done', 8);
    SpreadsheetApp.getUi().alert(msg);
  }
}

/**
 * Fetches and cleans scripture text from bible-api.com
 */
function fetchVerseFromApi(reference, translation) {
  // bible-api.com format: https://bible-api.com/{reference}?translation={translation}
  // Handles multi-word book names, verse ranges (e.g., Psalm 23:1-3)
  const cleanRef = reference.trim();
  const url = `https://bible-api.com/${encodeURIComponent(cleanRef)}?translation=${encodeURIComponent(translation)}`;
  
  try {
    const response = UrlFetchApp.fetch(url, {
      muteHttpExceptions: true,
      headers: {
        'Accept': 'application/json',
        'User-Agent': 'HolyThing-Android-Content-Backend/1.0'
      }
    });
    
    const statusCode = response.getResponseCode();
    const responseBody = response.getContentText();
    
    if (statusCode === 200) {
      const json = JSON.parse(responseBody);
      if (json.text) {
        return { success: true, text: cleanScriptureText(json.text) };
      } else {
        return { success: false, error: 'Empty text returned in API response' };
      }
    }
    
    // If requested translation failed and it wasn't WEB, fallback to WEB
    if (translation !== CONFIG.DEFAULT_TRANSLATION) {
      const fallbackUrl = `https://bible-api.com/${encodeURIComponent(cleanRef)}?translation=${CONFIG.DEFAULT_TRANSLATION}`;
      const fallbackResp = UrlFetchApp.fetch(fallbackUrl, { muteHttpExceptions: true });
      if (fallbackResp.getResponseCode() === 200) {
        const json = JSON.parse(fallbackResp.getContentText());
        if (json.text) {
          return { success: true, text: cleanScriptureText(json.text) };
        }
      }
    }
    
    return {
      success: false,
      error: `HTTP ${statusCode}: ${responseBody.substring(0, 150)}`
    };
  } catch (e) {
    return { success: false, error: `Exception: ${e.message}` };
  }
}

/**
 * Strips stray whitespace, newlines, verse number markers if present, and collapses spacing.
 */
function cleanScriptureText(text) {
  if (!text) return '';
  return text
    .replace(/\r/g, '')
    .replace(/\n+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Resets the row progress marker so fillVerseText starts from row 2 again.
 */
function resetFillProgress() {
  PropertiesService.getScriptProperties().deleteProperty(CONFIG.PROP_LAST_ROW);
  SpreadsheetApp.getActiveSpreadsheet().toast('Progress tracker reset to start.', 'HolyThing');
}

// ============================================================================
// 2. VALIDATE SHEET (validateSheet)
// ============================================================================

/**
 * Validates the sheet data:
 * - Required fields (id, format, hook, verse_ref, verse_text, active, length)
 * - Valid formats & lengths
 * - Unique IDs
 * - Story cards having series_id and series_order
 * - Active being TRUE/FALSE
 * - Flag mood/topic spelling and casing anomalies
 * - Highlights bad cells in the data sheet
 * - Outputs findings and distinct mood/topic lists into the Validation sheet.
 */
function validateSheet() {
  const sheet = getDataSheet();
  const dataRange = sheet.getDataRange();
  const data = dataRange.getValues();
  if (data.length < 2) {
    SpreadsheetApp.getUi().alert('Data sheet is empty.');
    return;
  }
  
  const headers = data[0].map(h => String(h).trim().toLowerCase());
  const headerMap = getHeaderMap(headers);
  
  // Clear any existing background formatting on data cells (excluding header row)
  if (data.length > 1) {
    sheet.getRange(2, 1, data.length - 1, headers.length).setBackground(null);
  }
  
  const issues = [];
  const seenIds = new Map(); // id -> first row number
  const moodCounts = new Map(); // mood (lowercased) -> { rawForms: Set, count }
  const topicCounts = new Map(); // topic (lowercased) -> { rawForms: Set, count }
  
  // Color styling for cell highlights
  const COLOR_ERROR = '#FCE8E6'; // light red
  const COLOR_WARNING = '#FEF7E0'; // light yellow
  const cellHighlights = []; // { row, col, color }
  
  for (let r = 2; r <= data.length; r++) {
    const row = data[r - 1];
    const idVal = headerMap.id !== undefined ? String(row[headerMap.id] || '').trim() : '';
    const formatVal = headerMap.format !== undefined ? String(row[headerMap.format] || '').trim().toLowerCase() : '';
    const hookVal = headerMap.hook !== undefined ? String(row[headerMap.hook] || '').trim() : '';
    const verseRefVal = headerMap.verse_ref !== undefined ? String(row[headerMap.verse_ref] || '').trim() : '';
    const verseTextVal = headerMap.verse_text !== undefined ? String(row[headerMap.verse_text] || '').trim() : '';
    const activeVal = headerMap.active !== undefined ? row[headerMap.active] : '';
    const lengthVal = headerMap.length !== undefined ? String(row[headerMap.length] || '').trim().toLowerCase() : '';
    const seriesIdVal = headerMap.series_id !== undefined ? String(row[headerMap.series_id] || '').trim() : '';
    const seriesOrderVal = headerMap.series_order !== undefined ? row[headerMap.series_order] : '';
    const toneVal = headerMap.tone !== undefined ? String(row[headerMap.tone] || '').trim() : '';
    const moodsRaw = headerMap.moods !== undefined ? String(row[headerMap.moods] || '').trim() : '';
    const topicsRaw = headerMap.topics !== undefined ? String(row[headerMap.topics] || '').trim() : '';
    
    // 1. Check ID
    if (!idVal) {
      issues.push({ row: r, id: '(empty)', col: 'id', severity: 'ERROR', message: 'Missing ID' });
      if (headerMap.id !== undefined) cellHighlights.push({ row: r, col: headerMap.id + 1, color: COLOR_ERROR });
    } else {
      if (seenIds.has(idVal)) {
        issues.push({
          row: r,
          id: idVal,
          col: 'id',
          severity: 'ERROR',
          message: `Duplicate ID: already used on row ${seenIds.get(idVal)}`
        });
        if (headerMap.id !== undefined) cellHighlights.push({ row: r, col: headerMap.id + 1, color: COLOR_ERROR });
      } else {
        seenIds.set(idVal, r);
      }
    }
    
    // 2. Check Format
    if (!formatVal) {
      issues.push({ row: r, id: idVal, col: 'format', severity: 'ERROR', message: 'Missing format' });
      if (headerMap.format !== undefined) cellHighlights.push({ row: r, col: headerMap.format + 1, color: COLOR_ERROR });
    } else if (!CONFIG.VALID_FORMATS.includes(formatVal)) {
      issues.push({
        row: r,
        id: idVal,
        col: 'format',
        severity: 'ERROR',
        message: `Invalid format "${formatVal}". Expected one of: ${CONFIG.VALID_FORMATS.join(', ')}`
      });
      if (headerMap.format !== undefined) cellHighlights.push({ row: r, col: headerMap.format + 1, color: COLOR_ERROR });
    }
    
    // 3. Check Hook
    if (!hookVal) {
      issues.push({ row: r, id: idVal, col: 'hook', severity: 'ERROR', message: 'Missing hook line' });
      if (headerMap.hook !== undefined) cellHighlights.push({ row: r, col: headerMap.hook + 1, color: COLOR_ERROR });
    }
    
    // 4. Check Verse Ref
    if (!verseRefVal) {
      issues.push({ row: r, id: idVal, col: 'verse_ref', severity: 'ERROR', message: 'Missing verse reference' });
      if (headerMap.verse_ref !== undefined) cellHighlights.push({ row: r, col: headerMap.verse_ref + 1, color: COLOR_ERROR });
    }
    
    // 5. Check Verse Text
    if (!verseTextVal) {
      const isCardActive = (activeVal === true || String(activeVal).toUpperCase() === 'TRUE');
      const sev = isCardActive ? 'ERROR' : 'WARNING';
      issues.push({
        row: r,
        id: idVal,
        col: 'verse_text',
        severity: sev,
        message: 'Empty verse_text (run Fill Verse Text)'
      });
      if (headerMap.verse_text !== undefined) cellHighlights.push({ row: r, col: headerMap.verse_text + 1, color: isCardActive ? COLOR_ERROR : COLOR_WARNING });
    }
    
    // 6. Check Length
    if (lengthVal && !CONFIG.VALID_LENGTHS.includes(lengthVal)) {
      issues.push({
        row: r,
        id: idVal,
        col: 'length',
        severity: 'WARNING',
        message: `Unknown length "${lengthVal}". Recommended: ${CONFIG.VALID_LENGTHS.join(', ')}`
      });
      if (headerMap.length !== undefined) cellHighlights.push({ row: r, col: headerMap.length + 1, color: COLOR_WARNING });
    }
    
    // 7. Check Story Series constraints
    if (formatVal === 'story') {
      if (!seriesIdVal) {
        issues.push({
          row: r,
          id: idVal,
          col: 'series_id',
          severity: 'ERROR',
          message: 'Story cards must have a series_id'
        });
        if (headerMap.series_id !== undefined) cellHighlights.push({ row: r, col: headerMap.series_id + 1, color: COLOR_ERROR });
      }
      const orderNum = Number(seriesOrderVal);
      if (seriesOrderVal === '' || isNaN(orderNum) || orderNum <= 0) {
        issues.push({
          row: r,
          id: idVal,
          col: 'series_order',
          severity: 'ERROR',
          message: `Story cards must have a valid positive series_order number (got "${seriesOrderVal}")`
        });
        if (headerMap.series_order !== undefined) cellHighlights.push({ row: r, col: headerMap.series_order + 1, color: COLOR_ERROR });
      }
    }
    
    // 8. Check Active flag
    if (activeVal !== true && activeVal !== false && String(activeVal).toUpperCase() !== 'TRUE' && String(activeVal).toUpperCase() !== 'FALSE') {
      issues.push({
        row: r,
        id: idVal,
        col: 'active',
        severity: 'ERROR',
        message: `active column must be TRUE or FALSE (got "${activeVal}")`
      });
      if (headerMap.active !== undefined) cellHighlights.push({ row: r, col: headerMap.active + 1, color: COLOR_ERROR });
    }
    
    // 9. Inspect Moods (casing, spelling, whitespace)
    if (moodsRaw) {
      inspectTagList(moodsRaw, 'moods', r, idVal, headerMap.moods, moodCounts, issues, cellHighlights, COLOR_WARNING);
    }
    
    // 10. Inspect Topics (casing, spelling, whitespace)
    if (topicsRaw) {
      inspectTagList(topicsRaw, 'topics', r, idVal, headerMap.topics, topicCounts, issues, cellHighlights, COLOR_WARNING);
    }
  }
  
  // Apply cell highlights
  cellHighlights.forEach(h => {
    sheet.getRange(h.row, h.col).setBackground(h.color);
  });
  
  // Check for casing discrepancies across distinct tags (e.g. "Anxious" and "anxious")
  checkCaseInconsistencies(moodCounts, 'mood', issues);
  checkCaseInconsistencies(topicCounts, 'topic', issues);
  
  // Output results to Validation Sheet
  writeValidationSheet(issues, moodCounts, topicCounts, data.length - 1);
  
  // Notify user
  const errorCount = issues.filter(i => i.severity === 'ERROR').length;
  const warningCount = issues.filter(i => i.severity === 'WARNING').length;
  const summaryMsg = `Validation Complete!\nErrors: ${errorCount}\nWarnings: ${warningCount}\nDistinct Moods: ${moodCounts.size}\nDistinct Topics: ${topicCounts.size}\nSee "${CONFIG.VALIDATION_SHEET_NAME}" sheet for details.`;
  SpreadsheetApp.getActiveSpreadsheet().toast(summaryMsg, 'HolyThing: Validation', 8);
  SpreadsheetApp.getUi().alert(summaryMsg);
}

/**
 * Splits comma-separated tags, checks for common anomalies, and aggregates distinct counts.
 */
function inspectTagList(rawStr, fieldName, rowIdx, cardId, colIdx, tagMap, issues, highlights, warnColor) {
  const parts = rawStr.split(',');
  let hasDoubleComma = false;
  let hasUntrimmedPart = false;
  
  parts.forEach(part => {
    const trimmed = part.trim();
    if (part !== trimmed) hasUntrimmedPart = true;
    if (part === '' && parts.length > 1) hasDoubleComma = true;
    
    if (trimmed.length > 0) {
      const lower = trimmed.toLowerCase();
      if (!tagMap.has(lower)) {
        tagMap.set(lower, { rawForms: new Set(), count: 0 });
      }
      const entry = tagMap.get(lower);
      entry.rawForms.add(trimmed);
      entry.count++;
      
      // Check for non-standard characters in tags
      if (/[^a-zA-Z0-9\s\-_]/.test(trimmed)) {
        issues.push({
          row: rowIdx,
          id: cardId,
          col: fieldName,
          severity: 'WARNING',
          message: `Special character in tag "${trimmed}"`
        });
      }
    }
  });
  
  if (hasDoubleComma) {
    issues.push({
      row: rowIdx,
      id: cardId,
      col: fieldName,
      severity: 'WARNING',
      message: `Extra comma or empty tag found in "${rawStr}"`
    });
    if (colIdx !== undefined) highlights.push({ row: rowIdx, col: colIdx + 1, color: warnColor });
  }
  if (hasUntrimmedPart) {
    issues.push({
      row: rowIdx,
      id: cardId,
      col: fieldName,
      severity: 'WARNING',
      message: `Irregular whitespace around tags in "${rawStr}"`
    });
    if (colIdx !== undefined) highlights.push({ row: rowIdx, col: colIdx + 1, color: warnColor });
  }
}

/**
 * Flags tags that appear with multiple inconsistent casings (e.g., "hopeful" vs "Hopeful").
 */
function checkCaseInconsistencies(tagMap, label, issues) {
  tagMap.forEach((data, lower) => {
    if (data.rawForms.size > 1) {
      issues.push({
        row: '-',
        id: 'GLOBAL',
        col: label,
        severity: 'WARNING',
        message: `Inconsistent casing for ${label} "${lower}": seen as [${Array.from(data.rawForms).join(', ')}]`
      });
    }
  });
}

/**
 * Writes validation findings and distinct moods/topics tables to the Validation sheet.
 */
function writeValidationSheet(issues, moodCounts, topicCounts, totalRows) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = ss.getSheetByName(CONFIG.VALIDATION_SHEET_NAME);
  if (!sheet) {
    sheet = ss.insertSheet(CONFIG.VALIDATION_SHEET_NAME);
  }
  sheet.clear();
  sheet.setTabColor('#EA4335');
  
  // Section 1: Summary Banner
  const errorCount = issues.filter(i => i.severity === 'ERROR').length;
  const warningCount = issues.filter(i => i.severity === 'WARNING').length;
  
  sheet.getRange(1, 1).setValue('HolyThing Content Validation Report').setFontWeight('bold').setFontSize(14);
  sheet.getRange(2, 1).setValue(`Generated: ${new Date().toISOString()} | Total Rows: ${totalRows} | Errors: ${errorCount} | Warnings: ${warningCount}`);
  
  // Section 2: Issues Table
  sheet.getRange(4, 1, 1, 5).setValues([['Row', 'Card ID', 'Field', 'Severity', 'Issue Description']])
    .setFontWeight('bold')
    .setBackground('#1A73E8')
    .setFontColor('#FFFFFF');
  
  if (issues.length > 0) {
    const issueRows = issues.map(iss => [iss.row, iss.id, iss.col, iss.severity, iss.message]);
    sheet.getRange(5, 1, issueRows.length, 5).setValues(issueRows);
    
    // Highlight severity column
    for (let i = 0; i < issues.length; i++) {
      const cell = sheet.getRange(5 + i, 4);
      if (issues[i].severity === 'ERROR') {
        cell.setBackground('#FCE8E6').setFontColor('#C5221F').setFontWeight('bold');
      } else {
        cell.setBackground('#FEF7E0').setFontColor('#B06000');
      }
    }
  } else {
    sheet.getRange(5, 1, 1, 5).setValues([['-', 'ALL', 'ALL', 'OK', 'No validation issues detected! Sheet is ready for production.']])
      .setBackground('#E6F4EA')
      .setFontColor('#137333');
  }
  
  // Section 3: Distinct Moods Table (Column G & H)
  const sortedMoods = Array.from(moodCounts.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  sheet.getRange(4, 7, 1, 2).setValues([['Distinct Moods (Onboarding)', 'Count']])
    .setFontWeight('bold')
    .setBackground('#34A853')
    .setFontColor('#FFFFFF');
  if (sortedMoods.length > 0) {
    const moodRows = sortedMoods.map(([mood, data]) => [mood, data.count]);
    sheet.getRange(5, 7, moodRows.length, 2).setValues(moodRows);
  }
  
  // Section 4: Distinct Topics Table (Column J & K)
  const sortedTopics = Array.from(topicCounts.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  sheet.getRange(4, 10, 1, 2).setValues([['Distinct Topics (Onboarding)', 'Count']])
    .setFontWeight('bold')
    .setBackground('#FBBC04')
    .setFontColor('#202124');
  if (sortedTopics.length > 0) {
    const topicRows = sortedTopics.map(([topic, data]) => [topic, data.count]);
    sheet.getRange(5, 10, topicRows.length, 2).setValues(topicRows);
  }
  
  sheet.autoResizeColumns(1, 11);
}

// ============================================================================
// 3. WEB APP ENDPOINT (doGet)
// ============================================================================

/**
 * Handles HTTP GET requests for the Web App.
 * Returns:
 * - { "upToDate": true, "version": "..." } if client version matches.
 * - { "version": "...", "updatedAt": "...", "count": N, "cards": [...] } otherwise.
 * Active rows only. Topics/moods parsed as arrays. series_order as number.
 */
function doGet(e) {
  try {
    const requestedSince = (e && e.parameter && e.parameter.since) ? String(e.parameter.since).trim() : null;
    
    // Check cached payload
    const cache = CacheService.getScriptCache();
    let cachedPayload = getFromCache(cache, CONFIG.CACHE_KEY_PAYLOAD);
    let cachedVersion = PropertiesService.getScriptProperties().getProperty(CONFIG.PROP_VERSION);
    let cachedUpdatedAt = PropertiesService.getScriptProperties().getProperty(CONFIG.PROP_UPDATED_AT);
    
    // If cache missed or version missing, build fresh payload
    if (!cachedPayload || !cachedVersion || !cachedUpdatedAt) {
      const freshData = buildCardsData();
      cachedPayload = JSON.stringify(freshData);
      cachedVersion = freshData.version;
      cachedUpdatedAt = freshData.updatedAt;
      
      // Store in CacheService (chunked) and PropertiesService
      putInCache(cache, CONFIG.CACHE_KEY_PAYLOAD, cachedPayload, CONFIG.CACHE_EXPIRATION_SEC);
      PropertiesService.getScriptProperties().setProperties({
        [CONFIG.PROP_VERSION]: cachedVersion,
        [CONFIG.PROP_UPDATED_AT]: cachedUpdatedAt
      });
    }
    
    // Check ?since=<version>
    if (requestedSince && requestedSince === cachedVersion) {
      return jsonResponse({
        upToDate: true,
        version: cachedVersion
      });
    }
    
    // Return full payload directly from cache string
    return ContentService.createTextOutput(cachedPayload)
      .setMimeType(ContentService.MimeType.JSON);
      
  } catch (err) {
    return jsonResponse({
      error: true,
      message: 'Failed to generate card feed: ' + err.message
    }, 500);
  }
}

/**
 * Reads active rows from sheet and compiles the card list, version hash, and timestamp.
 */
function buildCardsData() {
  const sheet = getDataSheet();
  const data = sheet.getDataRange().getValues();
  if (data.length < 2) {
    return {
      version: 'empty-0',
      updatedAt: new Date().toISOString(),
      count: 0,
      cards: []
    };
  }
  
  const headers = data[0].map(h => String(h).trim().toLowerCase());
  const headerMap = getHeaderMap(headers);
  const cards = [];
  
  for (let r = 1; r < data.length; r++) {
    const row = data[r];
    
    // Active filter: active === true or string 'TRUE'
    const activeVal = headerMap.active !== undefined ? row[headerMap.active] : false;
    const isActive = (activeVal === true || String(activeVal).trim().toUpperCase() === 'TRUE');
    if (!isActive) continue;
    
    const id = headerMap.id !== undefined ? String(row[headerMap.id] || '').trim() : '';
    if (!id) continue; // Skip cards without ID
    
    const format = headerMap.format !== undefined ? String(row[headerMap.format] || '').trim().toLowerCase() : 'verse';
    const hook = headerMap.hook !== undefined ? String(row[headerMap.hook] || '').trim() : '';
    const verseRef = headerMap.verse_ref !== undefined ? String(row[headerMap.verse_ref] || '').trim() : '';
    const verseText = headerMap.verse_text !== undefined ? String(row[headerMap.verse_text] || '').trim() : '';
    const translation = headerMap.translation !== undefined ? String(row[headerMap.translation] || '').trim().toUpperCase() : 'WEB';
    const insight = headerMap.insight !== undefined ? String(row[headerMap.insight] || '').trim() : '';
    const tone = headerMap.tone !== undefined ? String(row[headerMap.tone] || '').trim().toLowerCase() : '';
    const book = headerMap.book !== undefined ? String(row[headerMap.book] || '').trim() : '';
    const length = headerMap.length !== undefined ? String(row[headerMap.length] || '').trim().toLowerCase() : 'medium';
    const reflectionPrompt = headerMap.reflection_prompt !== undefined ? String(row[headerMap.reflection_prompt] || '').trim() : '';
    
    // Series fields
    const seriesId = headerMap.series_id !== undefined && String(row[headerMap.series_id] || '').trim() !== ''
      ? String(row[headerMap.series_id]).trim()
      : null;
      
    let seriesOrder = null;
    if (headerMap.series_order !== undefined && row[headerMap.series_order] !== '') {
      const parsedOrder = Number(row[headerMap.series_order]);
      if (!isNaN(parsedOrder)) {
        seriesOrder = parsedOrder;
      }
    }
    
    // Parse comma-separated topics and moods to clean lowercase arrays
    const topics = parseTagArray(headerMap.topics !== undefined ? row[headerMap.topics] : '');
    const moods = parseTagArray(headerMap.moods !== undefined ? row[headerMap.moods] : '');
    
    cards.push({
      id: id,
      format: format,
      hook: hook,
      verse_ref: verseRef,
      verse_text: verseText,
      translation: translation,
      insight: insight,
      topics: topics,
      moods: moods,
      tone: tone,
      book: book,
      length: length,
      series_id: seriesId,
      series_order: seriesOrder,
      reflection_prompt: reflectionPrompt
    });
  }
  
  // Compute deterministic SHA-256 version hash from cards payload
  const cardsFingerprint = JSON.stringify(cards);
  const hashBytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, cardsFingerprint, Utilities.Charset.UTF_8);
  const versionHash = hashBytes
    .map(b => (b < 0 ? b + 256 : b).toString(16).padStart(2, '0'))
    .join('')
    .substring(0, 16);
    
  return {
    version: versionHash,
    updatedAt: new Date().toISOString(),
    count: cards.length,
    cards: cards
  };
}

/**
 * Splits comma-separated string into clean unique lowercase string array.
 */
function parseTagArray(raw) {
  if (!raw) return [];
  const parts = String(raw).split(',');
  const results = [];
  const seen = new Set();
  
  parts.forEach(p => {
    const clean = p.trim().toLowerCase();
    if (clean.length > 0 && !seen.has(clean)) {
      seen.add(clean);
      results.push(clean);
    }
  });
  return results;
}

// ============================================================================
// CACHE & STORAGE UTILITIES (Handles Apps Script 100KB limit safely)
// ============================================================================

/**
 * Writes data into CacheService, chunking into <=90KB pieces if necessary.
 */
function putInCache(cache, key, value, expirationSec) {
  const chunkSize = CONFIG.CHUNK_CACHE_SIZE;
  if (value.length <= chunkSize) {
    cache.put(key, value, expirationSec);
    cache.remove(key + '_chunks');
    return;
  }
  
  const numChunks = Math.ceil(value.length / chunkSize);
  const chunkMap = {};
  chunkMap[key + '_chunks'] = String(numChunks);
  
  for (let i = 0; i < numChunks; i++) {
    chunkMap[key + '_' + i] = value.substring(i * chunkSize, (i + 1) * chunkSize);
  }
  
  cache.putAll(chunkMap, expirationSec);
}

/**
 * Reads and reassembles chunked data from CacheService.
 */
function getFromCache(cache, key) {
  const numChunksStr = cache.get(key + '_chunks');
  if (!numChunksStr) {
    return cache.get(key);
  }
  
  const numChunks = parseInt(numChunksStr, 10);
  const keys = [];
  for (let i = 0; i < numChunks; i++) {
    keys.push(key + '_' + i);
  }
  
  const chunks = cache.getAll(keys);
  let combined = '';
  for (let i = 0; i < numChunks; i++) {
    const part = chunks[key + '_' + i];
    if (!part) return null; // Incomplete or evicted chunk
    combined += part;
  }
  return combined;
}

/**
 * Invalidates cached payload and forces next request to regenerate version.
 */
function invalidateCache() {
  const cache = CacheService.getScriptCache();
  cache.remove(CONFIG.CACHE_KEY_PAYLOAD);
  cache.remove(CONFIG.CACHE_KEY_PAYLOAD + '_chunks');
  PropertiesService.getScriptProperties().deleteProperty(CONFIG.PROP_VERSION);
  PropertiesService.getScriptProperties().deleteProperty(CONFIG.PROP_UPDATED_AT);
}

/**
 * Menu action to clear cache and log details.
 */
function clearCacheAndReindex() {
  invalidateCache();
  SpreadsheetApp.getActiveSpreadsheet().toast('Cache invalidated. Fresh version will generate on next request.', 'HolyThing');
}

/**
 * Displays a modal dialog with JSON preview and endpoint instructions.
 */
function previewJson() {
  const data = buildCardsData();
  const sampleJson = JSON.stringify({
    version: data.version,
    updatedAt: data.updatedAt,
    count: data.count,
    sampleCard: data.cards.length > 0 ? data.cards[0] : null
  }, null, 2);
  
  const htmlOutput = HtmlService.createHtmlOutput(
    `<div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 12px; color: #202124;">
      <h3 style="margin-top: 0; color: #1A73E8;">HolyThing Endpoint Preview</h3>
      <p><b>Active Cards:</b> ${data.count} | <b>Version Hash:</b> <code>${data.version}</code></p>
      <p><b>Updated At:</b> ${data.updatedAt}</p>
      <p>Deploy as Web App to query: <code>?since=${data.version}</code></p>
      <hr style="border: 0; border-top: 1px solid #E0E0E0; margin: 12px 0;">
      <h4>Sample Response Payload (First Card):</h4>
      <pre style="background: #F8F9FA; border: 1px solid #DADCE0; padding: 12px; border-radius: 6px; font-size: 12px; max-height: 280px; overflow: auto;">${escapeHtml(sampleJson)}</pre>
    </div>`
  ).setWidth(560).setHeight(480);
  
  SpreadsheetApp.getUi().showModalDialog(htmlOutput, 'HolyThing - JSON Preview');
}

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Returns the primary data sheet ("Cards" or the first sheet).
 */
function getDataSheet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  return ss.getSheetByName(CONFIG.DATA_SHEET_NAME) || ss.getSheets()[0];
}

/**
 * Returns an existing sheet or creates a new one with header row.
 */
function getOrCreateSheet(name, headers) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = ss.getSheetByName(name);
  if (!sheet) {
    sheet = ss.insertSheet(name);
    if (headers && headers.length > 0) {
      sheet.getRange(1, 1, 1, headers.length).setValues([headers])
        .setFontWeight('bold')
        .setBackground('#5F6368')
        .setFontColor('#FFFFFF');
      sheet.setFrozenRows(1);
    }
  }
  return sheet;
}

/**
 * Maps header names to their 0-based column indices.
 */
function getHeaderMap(headers) {
  const map = {};
  headers.forEach((h, idx) => {
    map[h] = idx;
  });
  return map;
}

/**
 * Returns a JSON TextOutput response.
 */
function jsonResponse(obj, status) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Simple HTML escape utility for preview dialog.
 */
function escapeHtml(str) {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

