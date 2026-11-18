from pathlib import Path
import sys
root=Path(sys.argv[1])
p=root/'app/src/main/java/com/pockethighlighter/ReaderActivity.java'
s=p.read_text()
def replace(old,new):
    global s
    assert s.count(old)==1, f"Match count {s.count(old)}: {old[:90]}"
    s=s.replace(old,new)
replace('import android.view.Gravity;\n','import android.view.Gravity;\nimport android.view.MotionEvent;\n')
replace('    private LinearLayout pageBar, markerSettings;', '''    private LinearLayout pageBar, markerSettings;
    private FrameLayout highlightWorkArea;
    private boolean highlightPanelPositioned;
    private float panelTouchX, panelTouchY;
    private int panelTouchLeft, panelTouchTop;''')
replace('''        FrameLayout workArea = new FrameLayout(this);
        root.addView(workArea, new LinearLayout.LayoutParams(-1, 0, 1));''','''        FrameLayout workArea = new FrameLayout(this);
        highlightWorkArea = workArea;
        root.addView(workArea, new LinearLayout.LayoutParams(-1, 0, 1));''')
replace('''        dock.addView(markerSettings, item(169, -2, 0));
        markerSettings.setVisibility(View.GONE);''','''        // Floating overlay, independent of dock: dragging never draws on the PDF.
        FrameLayout.LayoutParams paletteParams = new FrameLayout.LayoutParams(
                dp(186), -2, Gravity.TOP | Gravity.LEFT);
        workArea.addView(markerSettings, paletteParams);
        markerSettings.setVisibility(View.GONE);''')
replace('''        pick.setText("HIGHLIGHT COLOR");''','''        pick.setText("☰ DRAG • HIGHLIGHT COLOR");
        pick.setContentDescription("Drag highlight color panel");''')
replace('''        markerSettings.addView(pick, item(-1, 28, 0));''','''        markerSettings.addView(pick, item(-1, 38, 0));
        View.OnTouchListener dragPalette = (view, event) -> movePaletteByTouch(event);
        pick.setOnTouchListener(dragPalette);
        markerSettings.setOnTouchListener(dragPalette);''')
replace('''            markerSettings.setVisibility(highlighting ? View.VISIBLE : View.GONE);
''','''            markerSettings.setVisibility(highlighting ? View.VISIBLE : View.GONE);
            if (highlighting) showDraggablePalette();
''')
replace('''        help.setText("Pinch to zoom • ✎ highlight • ⌫ erase marks • drafts 4 days");''','''        help.setText("Pinch to zoom • drag ☰ to move palette • ⌫ erase • drafts 4 days");''')
replace('''    private void addColorChip(LinearLayout row, String name, int color) {''','''    private void showDraggablePalette() {
        markerSettings.bringToFront();
        markerSettings.post(() -> {
            if (highlightWorkArea.getWidth() == 0 || markerSettings.getWidth() == 0) return;
            if (!highlightPanelPositioned) {
                int x = highlightWorkArea.getWidth() - markerSettings.getWidth() - dp(112);
                int y = (highlightWorkArea.getHeight() - markerSettings.getHeight()) / 2;
                positionPalette(x, y);
                highlightPanelPositioned = true;
            } else {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) markerSettings.getLayoutParams();
                positionPalette(p.leftMargin, p.topMargin);
            }
        });
    }

    private void positionPalette(int left, int top) {
        if (markerSettings == null || highlightWorkArea == null) return;
        int maxX = Math.max(0, highlightWorkArea.getWidth() - markerSettings.getWidth());
        int maxY = Math.max(0, highlightWorkArea.getHeight() - markerSettings.getHeight());
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) markerSettings.getLayoutParams();
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.leftMargin = Math.max(0, Math.min(maxX, left));
        p.topMargin = Math.max(0, Math.min(maxY, top));
        markerSettings.setLayoutParams(p);
    }

    private boolean movePaletteByTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) markerSettings.getLayoutParams();
                panelTouchLeft = p.leftMargin;
                panelTouchTop = p.topMargin;
                panelTouchX = event.getRawX();
                panelTouchY = event.getRawY();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                positionPalette(panelTouchLeft + Math.round(event.getRawX() - panelTouchX),
                        panelTouchTop + Math.round(event.getRawY() - panelTouchY));
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                return true;
            default:
                return false;
        }
    }

    private void addColorChip(LinearLayout row, String name, int color) {''')
p.write_text(s)
build=root/'app/build.gradle'
gradle=build.read_text()
assert gradle.count('versionCode 3') == 1 and gradle.count("versionName '1.2'")==1
build.write_text(gradle.replace('versionCode 3','versionCode 4').replace("versionName '1.2'","versionName '1.3'"))
print("PASS: draggable palette patch and version 1.3 applied")
