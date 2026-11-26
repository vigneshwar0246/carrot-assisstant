# Graph Report - java jarvis  (2026-11-26)

## Corpus Check
- Corpus is ~4,685 words - fits in a single context window. You may not need a graph.

## Summary
- 147 nodes · 253 edges · 12 communities (5 shown, 7 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 23 edges (avg confidence: 0.87)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Platform and IO
- Core Assistant Runtime
- Frontend HUD Runtime
- Reminders and Tests
- Product Capabilities
- Wake Mode State
- Command Parsing

## God Nodes (most connected - your core abstractions)
1. `JarvisApp` - 46 edges
2. `ReminderStore` - 13 edges
3. `Reminder` - 11 edges
4. `Carrot Local AI Assistant Command Center` - 9 edges
5. `WakeMode` - 8 edges
6. `CommandParser` - 6 edges
7. `Carrot Local Assistant` - 6 edges
8. `render()` - 5 edges
9. `esc()` - 4 edges
10. `getStatus()` - 4 edges

## Surprising Connections (you probably didn't know these)
- `Local Command-Center Frontend` --semantically_similar_to--> `Carrot Local AI Assistant Command Center`  [INFERRED] [semantically similar]
  README.md → web/index.html
- `Local-Only Operation` --semantically_similar_to--> `Local by Design`  [INFERRED] [semantically similar]
  README.md → web/index.html
- `Reminder Management` --semantically_similar_to--> `Reminders Workspace`  [INFERRED] [semantically similar]
  README.md → web/index.html
- `Voice, Wake-Word, Clap, and Typed Activation` --semantically_similar_to--> `Typed Command and Voice Interaction`  [INFERRED] [semantically similar]
  README.md → web/index.html
- `Voice, Wake-Word, Clap, and Typed Activation` --semantically_similar_to--> `Wake Mode Control`  [INFERRED] [semantically similar]
  README.md → web/index.html

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Carrot Multimodal Command Flow** — readme_multimodal_activation, web_index_wake_mode_control, web_index_command_interaction [INFERRED 0.85]
- **Carrot Local Reminder Experience** — readme_local_only_operation, readme_reminder_management, web_index_reminders_workspace, web_index_local_by_design [INFERRED 0.85]

## Communities (12 total, 7 thin omitted)

### Community 1 - "Core Assistant Runtime"
Cohesion: 0.13
Nodes (3): JarvisApp, Arguments, Conversation

### Community 2 - "Frontend HUD Runtime"
Cohesion: 0.17
Nodes (14): drawAmbient(), circuit(), frame(), drawWave(), esc(), getStatus(), render(), renderLogs() (+6 more)

### Community 3 - "Reminders and Tests"
Cohesion: 0.23
Nodes (3): Reminder, ReminderStore, JarvisAppTest

### Community 4 - "Product Capabilities"
Cohesion: 0.17
Nodes (14): Approved Application Launching, Carrot Local Assistant, Local Command-Center Frontend, Voice, Wake-Word, Clap, and Typed Activation, Packaged Windows Startup, Reminder Management, Carrot Assistant Core, Carrot Local AI Assistant Command Center (+6 more)

### Community 6 - "Wake Mode State"
Cohesion: 0.29
Nodes (4): WakeMode, BOTH, CARROT, CLAP

## Knowledge Gaps
- **8 isolated node(s):** `CARROT`, `CLAP`, `BOTH`, `Approved Application Launching`, `Packaged Windows Startup` (+3 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 51 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **7 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `JarvisApp` connect `Core Assistant Runtime` to `Platform and IO`, `Reminders and Tests`, `Local HTTP API`, `Wake Mode State`?**
  _High betweenness centrality (0.336) - this node is a cross-community bridge._
- **What connects `CARROT`, `CLAP`, `BOTH` to the rest of the system?**
  _8 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Platform and IO` be split into smaller, more focused modules?**
  _Cohesion score 0.0625 - nodes in this community are weakly interconnected._
- **Why does `ReminderStore` connect `Reminders and Tests` to `Platform and IO`, `Core Assistant Runtime`?**
  _High betweenness centrality (0.054) - this node is a cross-community bridge._
- **Should `Core Assistant Runtime` be split into smaller, more focused modules?**
  _Cohesion score 0.12701612903225806 - nodes in this community are weakly interconnected._
- **Why does `WakeMode` connect `Wake Mode State` to `Core Assistant Runtime`?**
  _High betweenness centrality (0.052) - this node is a cross-community bridge._