# Teletexting: A teletext editor and viewer in your pocket 

![App Icon](https://raw.githubusercontent.com/AleSla/Teletexting/refs/heads/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

A pure native Android application for viewing and editing teletext pages in multiple historical formats.

## 📋 Project Specifications

- **Package Name**: `bg.alesla.teletexting`
- **Minimum SDK**: 23 (Android 6.0)
- **Target SDK**: 33 (Android 13)
- **Language**: Kotlin (100%)
- **UI**: XML layouts 

## 🎯 Core Features

### File Format Support

The application supports **5 teletext formats and 1 image format**:

1. **MRG Systems TTI** (Teletext Interchange) - `.tti`
2. **Softel EP1** (Softel Page 1) - `.ep1`
3. **Teletext Packets (T42)** - `.t42`
4. **Binary Dump** (Raw page data) - `.bin`
5. **Cebra Teletext TTX** - `.ttx` (the app doesn't parse it properly, for future improvement) 
6. **Hi-Resolution PNG** (Export only) - `.png`

### Teletext Rendering Engine

The custom `TeletextView` implements Level 1 teletext specifications:

- **Grid System**: 40 columns × 25 rows
- **Level 1 Control Codes**:
  - Text colors (Red, Green, Yellow, Blue, Magenta, Cyan, White, Black)
  - Graphics colors and mosaic characters
  - Flash attribute (animated at 500ms intervals)
  - Double height text
  - Background colors (Black background, New background)
  - Hold graphics
  - Separated/Contiguous graphics
- **Multilingual support**: 13 different charsets

### Editing Features

- **File Operations**: Open, Save, Save As using Android Storage Access Framework
- **Page Navigation**: Previous/Next page buttons, and specific magazine/page/subpage selection
- **Cursor Control**: Tap any cell to place cursor
- **Character Input**: Tap cursor position to enter characters
- **Attribute Toggling**: Insert flash and other control codes
- **Color Palette**: Quick access to 8 teletext colors in 2 modes: alphanumeric and mosaic symbols
- **Visual Toggles**: Show/hide grid and control codes, black and white mode

### Status Display

The status bar shows:
- **Page Address**: Current page/subpage number
- **Character Position**: Column and row coordinates
- **Hex Value**: Character code at cursor
- **CRC Status**: Data integrity indicator (Green = valid, Red = invalid)

## 📁 Project Structure

```
bg.alesla.teletexting/
├── MainActivity.kt              # Main application activity
├── view/
│   └── TeletextView.kt         # Custom rendering view
├── parsers/
│   ├── TTIParser.kt            # MRG Systems TTI format
│   ├── EP1Parser.kt            # Softel EP1 format
│   ├── T42Parser.kt            # Teletext Packets
│   ├── BinaryParser.kt         # Raw binary format
│   └── TTXParser.kt            # Cebra TTX format
├── utils/
│   └── CRCCalculator.kt        # Data integrity checking
└── res/
    ├── layout/
    │   └── activity_main.xml   # Main UI layout
    └── menu/
        └── main_menu.xml       # Application menu
```

## 🔧 Technical Implementation

### TeletextView.kt

Custom View implementing teletext rendering:

- **Attribute Processing**: Scans each row left-to-right, tracking control code states
- **Background Drawing**: Respects double-height cells (spans 2 rows)
- **Character Rendering**: Uses custom pixelated fonts with proper baseline alignment
- **Double Height**: Single character drawn at 2× size spanning two cells, with proper clipping
- **Mosaic Graphics**: Draws 6-sixel graphics based on bit patterns
- **Flash Animation**: Toggles visibility every 500ms via Handler
- **Gesture Detection**: Touch coordinates scaled for proper cursor positioning
- **Custom Fonts**: Supports external TTF fonts for authentic teletext appearance

### Parser Architecture

Each parser implements:
- `parse()`: Converts format-specific bytes/text to standard `Array<IntArray>` (25×40)
- `serialize()`: Converts page data back to format-specific output

#### TTI Format
- Text-based with escape sequences
- Control codes escaped as `ESC + (code + 0x40)`
- Metadata: Page number, description, charset

#### EP1 Format
- Binary with 6-byte header: `0xFE 0x01 [4 bytes]`
- 960 bytes of page data (25 rows × 40 columns)

#### T42 Format
- 42-byte packets: 2 address bytes + 40 data bytes
- Hamming 8/4 encoding for addresses
- Row 0 contains page number in hamming-encoded header

#### TTX Format
- Similar to T42 with 42-byte packets
- Hamming encoding for addresses and page numbers
- Data bytes have parity bit set (OR 0x80)

#### Binary Format
- Raw 1000 bytes (25 rows × 40 columns)
- No header or metadata
- Auto-detects EP1 if signature present

### CRC Implementation

CRC-16-CCITT algorithm:
- Polynomial: 0x1021
- Initial value: 0xFFFF
- Processes all 1000 bytes of page data

## 🎨 User Interface

### Main Layout
- **Toolbar**: App title and menu actions
- **ScrollView**: Allows panning for large teletext display
- **TeletextView**: Centered rendering canvas
- **Navigation**: Page previous/next buttons and address bar with selections
- **Color Palette**: 16 color buttons for quick attribute insertion: 8 for alphanumeric, and 8 for mosaic symbols 

### Menu Options
- Undo / Redo
- Open file
- Save / Save As
- Export PNG
- Clear page
- Toggle grid overlay
- Show control codes
- Insert flash attribute
- Delete page
- Black and White mode

## 🚀 Build Instructions

### Prerequisites
- Android Studio Electric Eel or newer
- Kotlin plugin 1.7+
- Gradle 7.4+

### Build Steps

1. **Clone/Create Project**:
   ```bash
   # Create new Android project in Android Studio
   # Package: bg.alesla.teletexting
   # Minimum SDK: 23
   # Target SDK: 33
   ```

2. **Add Files**:
   - Copy all Kotlin files to corresponding packages
   - Copy XML files to `res/layout/` and `res/menu/`
   - Update `AndroidManifest.xml`
   - Update `build.gradle` dependencies

3. **Add Custom Fonts** (Optional but Recommended):
   - Create directory: `app/src/main/assets/fonts/`
   - Add `teletext.ttf` (normal height font)
   - Add `teletext_double.ttf` (double height font)

4. **Build**:
   ```bash
   ./gradlew assembleDebug
   ```

5. **Install**:
   ```bash
   ./gradlew installDebug
   ```

## 📱 Usage

### Opening Files
1. Tap **Open** in toolbar
2. Select a teletext file (.tti, .ep1, .t42, .ttx, .bin)
3. File loads and displays in grid

### Editing
1. **Tap any cell** to move cursor
2. **Tap cursor** to enter character
3. **Use color palette** to insert color control codes
4. **Menu → Insert Flash** to add flash attribute

### Saving
1. **Save** to overwrite current file
2. **Save As** to create new file with selected format
3. **Export PNG** to create high-resolution image

### Navigation
- **Prev/Next buttons** for multi-page documents
- **Scroll/zoom** the canvas for better visibility

## 🔒 Permissions

The app requests:
- `READ_EXTERNAL_STORAGE`: To open teletext files
- `WRITE_EXTERNAL_STORAGE`: To save files (Android 9 and below)

Android 10+ uses Scoped Storage (Storage Access Framework), which doesn't require permissions.

## 📚 Teletext Specifications

Based on **ETSI EN 300 706** standard:

### Character Set (Level 1)
- 0x00-0x1F: Control codes
- 0x20-0x7F: Displayable characters and graphics

### Control Codes
- 0x00-0x07: Text colors
- 0x08: Flash
- 0x09: Steady
- 0x0C: Normal height
- 0x0D: Double height
- 0x10-0x17: Graphics colors
- 0x19: Contiguous graphics
- 0x1A: Separated graphics
- 0x1C: Black background
- 0x1D: New background
- 0x1E: Hold graphics
- 0x1F: Release graphics

### Graphics Characters
- 0x20-0x3F and 0x60-0x7F: Contiguous graphics by default, transformed into separated, using control code 0x1A before their begining
- Each character represents 6 sixels (2×3 grid)
- Bit pattern: 0x01=top-left, 0x02=top-right, 0x04=mid-left, 0x08=mid-right, 0x10=bottom-left, 0x40=bottom-right

## 🔮 Future Enhancements

Features for future implementation and improvement:
- Copy/paste regions
- Search and replace
- Expanding Undo/Redo functionality
- Repairing Cebra TTX Parser
- Level 1.5 features and others

## 📄 License

No license specified.

## 🙏 Acknowledgments

- ETSI EN 300 706: Teletext specification
- Teletext Meddler SE: Reference implementation
- Sample files provided for testing

## 📞 Support

For issues or questions about this MVP implementation, please refer to the teletext specifications at https://www.etsi.org/deliver/etsi_i_ets/300700_300799/300706/01_60/ets_300706e01p.pdf
