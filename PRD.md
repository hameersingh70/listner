# PRODUCT REQUIREMENTS DOCUMENT
# RECALL — Personal AI Call Memory

**Version:** 1.1  
**Platform:** Android  
**Initial hardware:** Poco M4 series  
**Development language:** Kotlin  
**Data storage:** Local files only — no database  
**Audio preference:** M4A / AAC-LC  
**Distribution:** Private APK  
**Status:** Proposed MVP specification

---

## 1. Product Overview

### 1.1 Vision

Recall is an Android application designed to help users remember important details from phone conversations.

Instead of listening to complete recordings again, users can retrieve concise summaries, important facts, decisions and commitments.

The application stores conversations on the user's phone and organizes them into a searchable memory journal.

**Primary workflow:**

1. A normal phone conversation takes place.
2. A supported recording source produces an audio file.
3. Recall imports the recording.
4. The audio is stored on the phone.
5. The recording is transcribed.
6. AI extracts the relevant information.
7. The application generates a conversation summary.
8. The user can recall previous discussions by contact or date.

The preferred experience is automatic, but recording and file access are dependent on the target device.

### 1.2 Main problem

The user may forget details of conversations soon after a phone call.

A traditional call recorder preserves the information but does not make it easy to retrieve.

Recall should transform audio into structured information without requiring the user to write notes after every conversation.

### 1.3 Product objectives

- Preserve available conversation recordings.
- Minimize storage consumption.
- Organize conversations by contact and date.
- Support Hindi, English and mixed-language transcription.
- Generate structured AI summaries.
- Extract tasks, promises and decisions.
- Provide keyword-based retrieval.
- Keep persistent data locally.
- Avoid unnecessary permission requests.
- Operate without a database or permanent backend.

### 1.4 Success metrics

The MVP should demonstrate:

| Metric | Initial acceptance target |
|---|---|
| Import reliability | 100% of 20 supported test imports succeed |
| Duplicate handling | No duplicates among repeated imports of 10 identical files |
| Data recovery | All completed records recover after app restart |
| Audio storage | Approximately 22 MB/hour for 48 kbps AAC |
| Summary factual accuracy | At least 95% precision on manually verified extracted facts |
| Important detail recall | At least 90% recall on a curated set of clear, audible facts |
| Recording compatibility | Explicitly verified on the physical Poco device |

The accuracy targets are engineering goals, not guarantees. They require evaluation against manually reviewed transcripts.

---

## 2. Scope and Technical Constraints

### 2.1 Target device

Initially support a single Poco M4-series smartphone.

At startup, identify:

- Device model.
- Android version.
- Installed software build.
- Default dialer.
- Native call recording support.
- Accessible recording directories.
- Available storage.
- Battery/background-processing restrictions.

The exact Poco M4 variant and dialer must be confirmed through device diagnostics.

### 2.2 Calling integration

The app must continue using Android's existing cellular calling functionality.

The MVP should not replace the default Phone application.

A custom dialer can be evaluated later using `RoleManager`, `TelecomManager` and `InCallService`.

However, these APIs cannot provide an ordinary app with unrestricted cellular audio.

### 2.3 Recording limitations

The application must support these recording sources:

| Recording source | MVP requirement |
|---|---|
| Native phone recording files | Preferred |
| User-selected recording directory | Conditional automatic import |
| Android Sharesheet | Required |
| Manual audio selection | Required |
| Voice memo recorded within Recall | Optional MVP fallback |
| Direct cellular call audio interception | Not supported |

The system must not attempt to disable mandatory recording disclosures, suppress Android privacy indicators or bypass system audio restrictions.

Only conversations with appropriate participant knowledge and consent should be recorded.

### 2.4 Explicit non-goals

Version 1 will not include:

- Cloud storage or synchronization.
- SQLite, Room, Realm or another application database.
- User registration or accounts.
- Root or privileged system modifications.
- Cross-device synchronization.
- WhatsApp or other third-party VoIP capture.
- Live conversation transcription.
- Automatic AI responses during a call.
- A public Play Store release.

---

## 3. Audio Storage Requirements

### 3.1 Preferred audio specification

For recordings created or converted by Recall:

| Property | Configuration |
|---|---|
| Container | MPEG-4 Audio (.m4a) |
| Codec | AAC-LC |
| Target bitrate | 48 kbps |
| Channels | Mono |
| Sample rate | Device-supported, preferably 24 or 32 kHz for speech |
| Metadata | Duration, timestamp, source |
| Encoding mode | Native Android encoder where supported |

Do not assume all Poco firmware builds accept every requested AAC sample rate. Validate and use supported parameters.

Speech recognition may decode and resample audio to 16 kHz internally without changing the saved master recording.

### 3.2 Format handling

M4A is the preferred storage format, not a mandatory format for every imported file.

Rules:

1. If imported audio is already a suitable M4A file, preserve it.
2. Do not rename MP3, WAV or AMR audio to M4A without converting it.
3. Preserve the original file's format metadata.
4. Allow optional conversion for large uncompressed recordings.
5. Do not discard an original before validating the converted output.
6. Avoid repeated lossy encoding.
7. Use the best available source for transcription.

### 3.3 Expected storage

At 48 kbps AAC:

| Recorded audio | Approximate size |
|---|---:|
| 5 minutes | 1.8 MB |
| 15 minutes | 5.4 MB |
| 30 minutes | 10.8 MB |
| 1 hour | 21.6 MB |
| 30 hours | 648 MB |
| 90 hours | 1.94 GB |

Estimates exclude metadata and container overhead. Actual files may be larger or smaller.

### 3.4 Storage location

Default: Android's application-private persistent storage.

```text
filesDir/
└── recall/
    ├── config.json
    ├── contacts/
    ├── conversations/
    ├── journals/
    └── processing/
```

This stores the user's information on the phone without exposing it automatically to unrelated apps.

Important: app-private storage is removed when Recall is uninstalled or its data is cleared.

The MVP must therefore include a backup/export option.

### 3.5 Optional user-visible storage

Provide an option to export recordings and metadata to a user-selected directory, such as `Documents/Recall`.

Use Android's Storage Access Framework.

Do not assume the app can freely access all directories through the file manager or obtain unrestricted storage access.

### 3.6 Storage management

Display:

- Total recording size.
- Number of recordings.
- Total recorded duration.
- Transcript and metadata size.
- Available phone storage.
- Oldest stored recording.

Storage settings:

- Retain recordings indefinitely.
- Delete recordings after 7, 30 or 90 days.
- Retain transcript after deleting audio.
- Delete individual conversations.
- Export a complete conversation.

**Default retention: Keep until manually deleted.**

Automatic deletion must be opt-in.

---

## 4. File-Based Data Architecture

### 4.1 No database

All application records will be maintained as files.

The application must not introduce Room, SQLite or an embedded NoSQL database.

Structured data should use UTF-8 JSON files.

The file system is the source of truth.

### 4.2 Directory structure

Each conversation must have a unique directory.

```text
recall/
├── config.json
│
├── contacts/
│   ├── contact_a81c.json
│   └── contact_b92d.json
│
├── conversations/
│   └── 2026/
│       └── 10/
│           └── 21/
│               └── conv_a1b2c3/
│                   ├── audio.m4a
│                   ├── metadata.json
│                   ├── transcript.json
│                   ├── summary.json
│                   └── actions.json
│
├── journals/
│   └── 2026-10-21.json
│
└── processing/
    └── pending_jobs.json
```

Here, `audio.m4a` is illustrative; a source imported in another format retains its correct extension.

### 4.3 Conversation metadata

Example:

```json
{
  "schemaVersion": 1,
  "conversationId": "conv_a1b2c3",
  "contactId": "contact_a81c",
  "startedAt": null,
  "importedAt": "2026-10-21T15:45:00+05:30",
  "durationSeconds": 540,
  "direction": "UNKNOWN",
  "recordingSource": "SHARE_INTENT",
  "originalFileName": "call_001.m4a",
  "storedFileName": "audio.m4a",
  "mimeType": "audio/mp4",
  "audioCodec": "AAC-LC",
  "fileSizeBytes": 3240000,
  "sha256": "example-content-hash",
  "consentAttested": true,
  "status": "IMPORTED",
  "processingAttempts": 0
}
```

The content hash is a placeholder in this example; the implementation must calculate a real SHA-256 digest.

### 4.4 Data persistence rules

- Write metadata using temporary files followed by atomic replacement where supported.
- Do not expose partially imported recordings as complete.
- Update each conversation's state only after the required files are safely written.
- Validate JSON schema versions during loading.
- Recover incomplete processing jobs during startup.
- Detect orphaned audio and metadata.
- Use unique IDs rather than timestamps alone.
- Deduplicate imported recordings using content hashes.
- Rebuild temporary indexes from source files.

### 4.5 Search architecture

On app startup, read conversation metadata into an in-memory index.

Index fields:

- Conversation ID.
- Contact.
- Conversation date.
- Duration.
- Processing status.
- Overview.
- Searchable transcript content.

Use Kotlin collections for the initial implementation.

Searching the full transcript can be implemented through normalized text matching.

Do not store a persistent database-backed full-text index.

A memory-based index is acceptable for the MVP, but its performance must be tested as the recording count grows.

For the first version, optimize for hundreds of conversations rather than tens of thousands.

---

## 5. Recording Import Module

### FR-001: Manual import

The user can import an existing audio file.

Supported formats should include:

- M4A/AAC.
- MP3.
- WAV.
- AMR.
- Other formats supported by the installed decoder pipeline.

Functional requirements:

1. Open Android's document picker.
2. Select audio.
3. Validate supported format.
4. Extract available metadata.
5. Calculate file hash.
6. Check for duplicates.
7. Copy into Recall storage.
8. Save metadata.
9. Add to processing queue.

**Acceptance:** Importing a valid supported file must produce a playable local recording.

### FR-002: Android share integration

Recall should appear in the system's sharing options for audio files.

The user can select a recording and choose **Share → Recall**.

The app must handle incoming content URIs safely.

### FR-003: Automatic file import

If the Poco exposes an accessible recording folder, the user can grant access through Android's folder picker.

Recall should periodically scan the selected directory for completed recording files.

Requirements:

- Persist permitted directory access.
- Avoid importing files that are still being written.
- Detect new files.
- Deduplicate existing files.
- Avoid costly repeated full scans.
- Stop scanning if permission is revoked.
- Report inaccessible directories.
- Recover after normal restarts.

Automatic discovery is conditional on folder accessibility. Android background execution may delay scanning.

### FR-004: Recording source diagnostics

Provide a diagnostic screen showing:

```text
Device: Poco M4 [detected model]
Android: [version]
Default Dialer: [package]
Native Recorder: Not verified
Recording Folder: Not configured
Folder Access: Not granted
Automatic Import: Disabled
```

Diagnostics must represent the actual observed device state.

### FR-005: In-app voice memo

As a fallback, allow the user to manually record a personal voice note.

Save the memo as M4A/AAC.

This is microphone recording, not cellular call recording.

Request microphone permission only for this feature.

---

## 6. Background Processing

### 6.1 State machine

```text
IMPORTED
   ↓
VALIDATING
   ↓
READY
   ↓
TRANSCRIBING
   ↓
TRANSCRIBED
   ↓
SUMMARIZING
   ↓
COMPLETED
```

Failure states:

- INVALID_AUDIO
- UNSUPPORTED_FORMAT
- PROCESSING_FAILED
- NETWORK_UNAVAILABLE
- RETRY_PENDING
- CANCELLED

### 6.2 Scheduling architecture

Use:

- Kotlin coroutines for active in-app processing.
- Android JobScheduler for appropriate deferred jobs.
- JSON files for application-owned job state.
- Retry/resume logic driven by persisted file state.

Avoid WorkManager in the strict no-database version because its implementation uses an internal persistence database.

Platform background restrictions still apply, and execution may be delayed.

### 6.3 Crash recovery

When Recall starts:

1. Load the pending processing manifest.
2. Verify each referenced conversation exists.
3. Reconcile pending states with existing output files.
4. Reset interrupted operations to a retryable state.
5. Resume allowed processing.
6. Avoid repeating successfully completed work.

Processing must be idempotent.

### 6.4 Processing configuration

User settings should include:

- Process imported recordings automatically.
- Process only on Wi-Fi.
- Permit or disable remote AI processing.
- Maximum parallel processing jobs.
- Retry failed processing.
- Manual processing trigger.

Do not silently upload a recording when remote AI processing is disabled.

---

## 7. Speech Transcription

### FR-006: Audio transcription

The application must support mixed Hindi-English conversational speech.

Examples:

- "Kal meeting kitne baje hai?"
- "Payment Monday tak kar dena."
- "I already shared the quotation."

The transcript should preserve the language and intended wording as accurately as possible.

### 7.1 Initial AI architecture

The first implementation may connect to a configurable transcription service.

Example architecture:

```text
Poco M4
    |
    | User-authorized processing
    v
Transcription Service
    |
    | Transcript response
    v
Poco M4 Local Storage
```

The service must not require a persistent database.

If the user selects a self-hosted service, processing can be configured to avoid retaining uploaded audio after completion.

An external third-party provider may have its own retention policies; these must be disclosed.

### 7.2 Offline behavior

The following features must work without internet:

- Browsing previously saved recordings.
- Listening to audio.
- Viewing existing summaries.
- Keyword searching available records.
- Editing contact associations.
- Deleting recordings.
- Creating manual notes.

If transcription requires a network connection, the recording must remain queued until processing can resume.

### 7.3 Transcript JSON

```json
{
  "schemaVersion": 1,
  "conversationId": "conv_a1b2c3",
  "language": "hi-en",
  "status": "COMPLETED",
  "segments": [
    {
      "id": "segment_001",
      "startMs": 0,
      "endMs": 4200,
      "speaker": null,
      "text": "Kal meeting kitne baje hai?"
    },
    {
      "id": "segment_002",
      "startMs": 4200,
      "endMs": 6500,
      "speaker": null,
      "text": "Three PM."
    }
  ]
}
```

Speaker identification should not be invented when the model cannot reliably distinguish speakers.

---

## 8. AI Summarization

### FR-007: Conversation summary

Every successfully transcribed conversation should generate:

1. Overview.
2. Main discussion topics.
3. Important facts.
4. Decisions.
5. Action items.
6. Promises or commitments.
7. Dates and deadlines.
8. Unresolved questions.

### 8.1 Example output

```json
{
  "conversationId": "conv_a1b2c3",
  "overview": "Discussed tomorrow's meeting.",
  "topics": [
    "Meeting timing"
  ],
  "decisions": [
    {
      "description": "Meeting at 3 PM tomorrow.",
      "evidenceSegmentIds": [
        "segment_001",
        "segment_002"
      ]
    }
  ],
  "actionItems": [],
  "unresolvedQuestions": []
}
```

This is illustrative output, not an actual recorded conversation.

### 8.2 AI reliability rules

The model must:

- Use only transcript-supported information.
- Never invent monetary values.
- Never invent deadlines.
- Distinguish a proposal from a finalized decision.
- Avoid assigning commitments to an unidentified speaker.
- Preserve contradictory statements.
- Mark uncertainty explicitly.
- Reference the supporting transcript segments when available.

### 8.3 User corrections

Allow users to edit summaries.

Store original AI outputs and user corrections separately, or maintain an explicit revision history.

Corrections must survive regeneration of a summary.

---

## 9. Contact-Based Memory

### FR-008: Person timeline

Each selected contact should have a memory profile.

Show:

- Name or alias.
- List of associated conversations.
- Last conversation.
- Important topics.
- Confirmed action items.
- Searchable conversation history.

### 9.1 Contact association

Contact integration is optional.

If contact permission is not granted, users can create names manually.

The app must not automatically attribute a recording to a person based only on an uncertain filename or model guess.

### 9.2 Long-term memory

For MVP, long-term memory is chronological retrieval.

AI-based reasoning across many conversations is deferred to a later release.

---

## 10. Daily Journal

### FR-009: Daily recap

Create one JSON journal per date.

Example:

```json
{
  "date": "2026-10-21",
  "conversationIds": [
    "conv_a1b2c3"
  ],
  "overview": "One imported conversation discussed a meeting.",
  "pendingActions": [],
  "importantDecisions": [
    "Meeting scheduled at 3 PM."
  ],
  "lastGeneratedAt": "2026-10-21T22:00:00+05:30"
}
```

### 10.1 Generation behavior

- Generate journal entries from successfully processed conversations.
- Update or regenerate a journal when new recordings for that date become available.
- Avoid duplicate entries.
- Use the actual conversation date when verified.
- Otherwise use import date and indicate that the call date is unknown.
- Permit manual regeneration.

The daily journal represents only available recordings, not necessarily every call.

---

## 11. User Interface

### Screen 1 — Dashboard

Show:

- Today's imported conversations.
- Recent conversation summaries.
- Pending action items.
- Processing status.
- Import button.
- Search bar.

### Screen 2 — Conversation

Show:

- Contact name.
- Date and duration.
- M4A player.
- Generated overview.
- Extracted decisions.
- Action items.
- Full transcript.
- Edit/delete/export controls.

### Screen 3 — People

Show a list of manually saved or linked contacts.

Selecting a person opens their conversation timeline.

### Screen 4 — Daily Journal

Calendar-based overview of completed summaries and relevant action items.

### Screen 5 — Search

Support search by:

- Contact name.
- Keywords.
- Dates.
- Summary text.
- Transcript text.

### Screen 6 — Storage Manager

Show:

- Total storage consumed.
- Recording count.
- File format distribution.
- Retention options.
- Backup/export.
- Cleanup options.

### Screen 7 — Settings

Show:

- Device compatibility diagnostics.
- Recording import configuration.
- AI provider settings.
- Storage location and usage.
- Privacy/consent controls.
- Processing restrictions.
- Backup and restore.
- Application version.

### UI design principles

- Clean Android-native interface.
- Jetpack Compose and Material 3.
- Dark and light themes.
- Fast access to recent conversations.
- Minimal navigation.
- Clear distinction between recordings, transcripts and manual notes.
- No unnecessary overlays during normal phone calls.

---

## 12. Privacy and Security

### 12.1 Data protection

All persistent user records must remain in local phone storage unless the user explicitly authorizes an external processing operation.

Recommended baseline:

- Android application-private files.
- Device-provided file-based encryption.
- Optional biometric application lock.
- Secure handling of content URIs.
- No raw transcript logging.
- No automatic cloud backup of sensitive recordings.
- No third-party analytics SDK during MVP.

### 12.2 Recording consent

Recording must be based on an appropriate recording source and participant consent.

The app should support:

- A contact-level consent record.
- Exclusion of contacts.
- Manual deletion.
- Recording import acknowledgment.
- Clear disclosure when third-party AI is used.

The application must not attempt to hide mandatory recording indicators or announcements.

### 12.3 Permissions

| Permission or API | Requirement |
|---|---|
| Internet | Optional AI processing |
| Storage Access Framework | Manual/folder import |
| READ_CONTACTS | Optional contact association |
| RECORD_AUDIO | Only for in-app voice memos |
| READ_CALL_LOG | Not required |
| MANAGE_EXTERNAL_STORAGE | Not required |
| Accessibility Service | Not required |
| Default Dialer role | Not required for MVP |

### 12.4 Deletion

Deleting a conversation must remove its managed audio, transcripts, summaries and action items, according to the user's selected deletion options.

A deletion operation must also invalidate any derived in-memory search index entries.

If the original imported file exists elsewhere, the app must explain that deleting Recall's copy does not delete the source.

---

## 13. Non-Functional Requirements

### Reliability

- No lost completed imports during normal app restart.
- No duplicate conversations from repeated identical file imports.
- Recover after interruption or process termination.
- No corrupted JSON after interrupted writes.
- Respect source permission revocation.

### Performance

Initial engineering targets:

- Dashboard available within 2 seconds for 500 indexed conversations on the test device.
- Search response below 500 ms for common queries across 500 conversations, after indexing.
- Audio playback begins within 1 second when the audio is locally available.
- Avoid loading all full audio files into RAM.
- Stream large recording imports.

These targets must be measured on the physical Poco, not assumed from an emulator.

### Storage

- Check available space before copying recordings.
- Warn when storage falls below a configurable threshold.
- Avoid unnecessary duplicate audio files.
- Remove abandoned temporary files.
- Never automatically remove a retained original after an unverified conversion.

### Battery

- Avoid constant polling.
- Use scheduled or event-triggered discovery where supported.
- Avoid unnecessary foreground services.
- Respect Android background restrictions.
- Provide a Wi-Fi-only processing option.

---

## 14. Testing Requirements

### T01 — Device compatibility

Verify the actual dialer, Android version and recording capability.

### T02 — Audio quality

Test:

- Short call recording.
- Long call recording.
- Hindi conversation.
- English conversation.
- Mixed Hindi-English conversation.
- Different recording quality conditions.
- Files produced by available native recording methods.

### T03 — Audio compatibility

Test M4A/AAC playback and decoding.

Test file import with different supported formats.

Compare transcription accuracy for original audio versus compressed or converted audio.

### T04 — Storage

Verify:

- Files are created correctly.
- Metadata survives restart.
- Duplicate detection works.
- Audio deletion works.
- Storage calculations are correct.
- Backup and restore recover complete conversations.

### T05 — Background processing

Test:

- App minimized.
- Screen locked.
- Low battery.
- Network interrupted.
- App process terminated.
- Device reboot.
- Imported file still being written.

### T06 — AI evaluation

Create a manually annotated test set of at least 20 conversations.

Measure:

- Named entity accuracy.
- Number and date accuracy.
- Decision precision.
- Action-item precision and recall.
- Incorrect attribution.
- Unsupported or hallucinated facts.

---

## 15. Development Milestones

### Phase 0 — Device feasibility (1–2 days)

Deliverables:

- Device diagnostic page.
- Dialer inspection.
- Test of native recording availability.
- File-access proof of concept.
- Verified recording/import decision.

**Go/no-go:** Do not proceed under the assumption that automatic call capture works until it is demonstrated.

### Phase 1 — Filesystem foundation (2–3 days)

Deliverables:

- Kotlin project.
- Jetpack Compose navigation.
- Local directory creation.
- JSON serialization.
- Manual import.
- Audio playback.
- Content-hash deduplication.
- Crash-safe metadata writes.

### Phase 2 — Recording ingestion (2–3 days)

Deliverables:

- Android Share integration.
- Folder access selection.
- Conditional automatic import.
- Source metadata extraction.
- Retry handling.

### Phase 3 — AI integration (3–4 days)

Deliverables:

- Transcription provider abstraction.
- Configurable processing service.
- Hindi-English transcript output.
- Structured summarization.
- Transcript and summary JSON persistence.
- Processing status and error handling.

### Phase 4 — Memory experience (2–3 days)

Deliverables:

- Conversation timeline.
- Contact association.
- Keyword search.
- Daily journal.
- Action-item presentation.

### Phase 5 — Validation and security (2–3 days)

Deliverables:

- Poco device tests.
- Data-recovery testing.
- Storage management.
- Privacy configuration.
- Backup/export.
- Release APK.

**Estimated total:** Approximately 12–18 focused development days after resolving the fundamental recording feasibility question. Additional time may be required for device-specific issues and transcription accuracy improvements.

---

## 16. Major Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Poco does not expose two-way recording | Critical | Support available recordings and voice notes; do not claim unsupported capture |
| Recorder files are inaccessible | High | Android Share/manual import |
| Android limits background execution | High | JobScheduler, retries and file-state recovery |
| Poor mixed-language transcription | High | Test real audio; compare model configurations |
| AI fabricates conversation details | High | Evidence-linked structured summaries |
| No database makes search slower | Medium | In-memory index with measured limits |
| App uninstall removes private files | High | Backup/export and clear warnings |
| Large audio consumes storage | Medium | AAC compression and retention controls |
| Private conversations reach an AI provider | High | Explicit per-provider authorization and local/self-hosted option |

---

## 17. Definition of Done

The initial MVP is complete when:

- [ ] The application installs successfully on the selected Poco M4.
- [ ] Device and dialer diagnostics report verified capabilities.
- [ ] The app imports a supported recording.
- [ ] The recording is stored locally and remains playable.
- [ ] Available automatic import works, or manual import is clearly provided as the supported alternative.
- [ ] The user can associate a conversation with a contact.
- [ ] A transcription can be generated.
- [ ] AI produces a structured, evidence-based summary.
- [ ] The summary and transcript are stored as JSON.
- [ ] The dashboard retrieves local conversations.
- [ ] Keyword search works.
- [ ] A daily journal can be generated.
- [ ] The app recovers completed data after restart.
- [ ] Audio and metadata can be deleted.
- [ ] The user can export and restore important data.
- [ ] No application database is created.
- [ ] Recording privacy protections and participant consent are respected.

---

## 18. Final Technical Architecture

```text
                POCO M4
                   |
         Supported Audio Source
                   |
       +-----------+------------+
       |                        |
 Native Recording        Manual Import
 (when available)        or Voice Memo
       |                        |
       +-----------+------------+
                   |
           Import Manager
                   |
          Audio Validation
                   |
           File Deduplication
                   |
        Local Recording Storage
                 M4A
                   |
          Processing Queue
             JSON files
                   |
          Transcription API
          (or local model)
                   |
           transcript.json
                   |
            Summary Engine
                   |
            summary.json
                   |
         +---------+---------+
         |                   |
     Search Index       Daily Journal
      (in RAM)             (JSON)
         |                   |
         +---------+---------+
                   |
              Compose UI
```

### Core implementation rule

**Every conversation must be reconstructable from its local files, without a database, network connection, or backend service.**

AI processing may require a network connection, but previously completed memories must remain accessible offline.

### Future enhancement

Once this MVP is demonstrated on the Poco M4, evaluate a separate custom dialer module for improved call-related UI and context.

Do not couple core memory functionality to the dialer.

---

## 19. First Implementation Task

Before implementing transcription, create a small Kotlin diagnostic application that:

1. Detects the precise Poco model and OS version.
2. Identifies the default dialer.
3. Allows selecting an existing recording.
4. Imports and saves the recording locally.
5. Plays it back.
6. Reads its codec, duration and size.
7. Saves a corresponding metadata JSON file.
8. Restarts and successfully reconstructs the recording entry from files.

Then test the availability of supported automatic recording import.

**This is the first engineering milestone because it proves that the foundation of Recall works before adding complex AI features.**

