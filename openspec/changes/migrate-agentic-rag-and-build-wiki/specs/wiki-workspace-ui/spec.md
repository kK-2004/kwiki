## ADDED Requirements

### Requirement: Three-column Wiki workspace
The desktop workspace SHALL present global navigation, Wiki search/tree navigation, and the active content surface as distinct columns modeled on the approved reference layout.

#### Scenario: Reader opens the Wiki workspace on desktop
- **WHEN** the viewport is at least 1024 CSS pixels wide
- **THEN** global navigation, searchable page tree, and active page content are simultaneously visible without overlapping

### Requirement: Searchable and operable knowledge tree
The workspace SHALL support text filtering, knowledge/summary switching, node expansion, page selection, and create controls using mouse and keyboard.

#### Scenario: Reader filters pages
- **WHEN** the reader enters a search phrase
- **THEN** matching pages and their ancestor path remain visible and the result count updates

#### Scenario: Reader selects a tree page
- **WHEN** the reader activates a page node by click or keyboard
- **THEN** the node becomes selected and the content surface displays that page without reloading the workspace shell

### Requirement: Reading surface with provenance
The reading surface SHALL display breadcrumb, title, status/type, version, update time, rendered Markdown, links, backlinks, and source documents available to the reader.

#### Scenario: Reader opens a published page
- **WHEN** a page is selected
- **THEN** its rendered content and readable provenance metadata appear with controls appropriate to the reader's role

### Requirement: Editing and preview workflow
Authorized editors SHALL be able to enter visual editing, switch between edit and preview, save a draft, publish, and leave without silently losing unsaved changes.

#### Scenario: Editor previews changes
- **WHEN** an editor changes content and selects preview
- **THEN** the content surface renders the pending Markdown without publishing it

#### Scenario: Editor attempts to leave with unsaved changes
- **WHEN** an editor navigates away while local changes are unsaved
- **THEN** the workspace asks whether to discard or continue editing

### Requirement: Summary and revision history panels
The workspace SHALL expose generated/manual summary content and revision history without removing the reader's position in the knowledge tree.

#### Scenario: Reader opens summary mode
- **WHEN** the reader selects the summary tab
- **THEN** the middle column lists available summaries and the active surface shows summary provenance

#### Scenario: Editor opens revision history
- **WHEN** an editor activates history
- **THEN** a panel lists revision author, time, state, and change note with compare and restore actions allowed by role

### Requirement: Responsive and accessible navigation
The workspace SHALL collapse side columns into accessible drawers below desktop width and SHALL provide labels, focus indication, and semantic controls for interactive elements.

#### Scenario: Workspace opens on a narrow viewport
- **WHEN** the viewport is below 1024 CSS pixels
- **THEN** page content remains primary and each navigation column can be opened and closed without losing the selected page

#### Scenario: Keyboard user navigates controls
- **WHEN** a user tabs through search, tree, tabs, and page actions
- **THEN** focus order is logical, focus is visible, and controls expose meaningful accessible names
