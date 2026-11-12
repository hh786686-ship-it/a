#!/usr/bin/env python3
from pathlib import Path
root = Path("pocket-v2/build/app")
pkg = root/"src/main/java/com/pockethighlighter"
def edit(file, old, new):
    p = file
    s = p.read_text()
    count=s.count(old)
    if count != 1: raise ValueError(f"Expected exactly 1 match in {p.name}, got {count}: {old[:90]!r}")
    p.write_text(s.replace(old,new))
gradle=root/"build.gradle"
edit(gradle, "versionCode 2\n        versionName '1.1'", "versionCode 3\n        versionName '1.2'")
a=pkg/"AnnotationStore.java"
edit(a,"    private final HashMap<Integer, ArrayList<Stroke>> pages = new HashMap<>();",
"""    // Expire private unfinished strokes after four days without a saved session.
    private static final long FOUR_DAYS_MS = 4L * 24 * 60 * 60 * 1000;
    private final HashMap<Integer, ArrayList<Stroke>> pages = new HashMap<>();

    /** Erase only stored strokes, never the source file. */
    boolean eraseAt(int page, float x, float y, float radius) {
        ArrayList<Stroke> strokes = pages.get(page);
        if (strokes == null) return false;
        boolean changed = false;
        for (int i=strokes.size()-1; i>=0; i--) {
            Stroke stroke=strokes.get(i);
            float threshold=radius+stroke.width*0.5f;
            float sq=threshold*threshold;
            boolean hit=false;
            for (int j=0; j<stroke.points.size(); j++) {
                PointF pt=stroke.points.get(j);
                if (distanceSquared(x,y,pt.x,pt.y)<=sq) { hit=true; break; }
                if (j>0) {
                    PointF prev=stroke.points.get(j-1);
                    float dx=pt.x-prev.x,dy=pt.y-prev.y;
                    float len=dx*dx+dy*dy;
                    float t=len==0?0:Math.max(0,Math.min(1,((x-prev.x)*dx+(y-prev.y)*dy)/len));
                    if (distanceSquared(x,y,prev.x+t*dx,prev.y+t*dy)<=sq) { hit=true; break; }
                }
            }
            if (hit) { strokes.remove(i); changed=true; }
        }
        return changed;
    }
    private static float distanceSquared(float a,float b,float c,float d) {
        float dx=a-c,dy=b-d;
        return dx*dx+dy*dy;
    }
    static void pruneExpired(Context context) {
        File dir=new File(context.getFilesDir(), "highlights");
        File[] files=dir.listFiles();
        if (files==null) return;
        long now=System.currentTimeMillis();
        for (File file:files) {
            if (file.getName().endsWith(".json") && file.lastModified()>0 &&
                now-file.lastModified()>FOUR_DAYS_MS) file.delete();
        }
    }
""")
edit(a,"        if (!file.exists()) return store;\n", """        if (!file.exists()) return store;
        if (System.currentTimeMillis()-file.lastModified()>FOUR_DAYS_MS) {
            file.delete();
            return store;
        }
""")
d=pkg/"DocumentView.java"
edit(d,"    private boolean highlightMode;\n", "    private boolean highlightMode;\n    private boolean eraserMode;\n    private boolean erasedDuringGesture;\n")
edit(d,"    void setPenColor(int color) { highlighterColor = color; }",
"""    void setEraserMode(boolean enabled) {
        eraserMode=enabled;
        if (enabled) { highlightMode=false; finishStroke(); }
        invalidate();
    }
    private void eraseAt(float x,float y) {
        if (annotations==null || !inside(x,y)) return;
        float radius=dp(13)/Math.max(scale,0.001f);
        if (annotations.eraseAt(page,docX(x),docY(y),radius)) {
            erasedDuringGesture=true;
            invalidate();
        }
    }
    void setPenColor(int color) { highlighterColor = color; }""")
edit(d,"                pinchHappened = false;\n                if (highlightMode) startStroke(lastX, lastY);",
"                pinchHappened = false;\n                erasedDuringGesture = false;\n                if (highlightMode) startStroke(lastX, lastY);")
edit(d,"                } else if (!multiTouch && highlightMode) {\n                    moveStroke(ev.getX(), ev.getY());",
"                } else if (!multiTouch && !pinchHappened && eraserMode) {\n                    eraseAt(ev.getX(), ev.getY());\n                } else if (!multiTouch && highlightMode) {\n                    moveStroke(ev.getX(), ev.getY());")
edit(d,"            case MotionEvent.ACTION_UP:\n                finishStroke();",
"""            case MotionEvent.ACTION_UP:
                if (eraserMode && !pinchHappened) eraseAt(ev.getX(),ev.getY());
                if (erasedDuringGesture && listener!=null) listener.onStrokeComplete();
                erasedDuringGesture=false;
                finishStroke();""")
edit(d,"            case MotionEvent.ACTION_CANCEL:\n                cancelStroke();",
"            case MotionEvent.ACTION_CANCEL:\n                cancelStroke();\n                erasedDuringGesture=false;")
r=pkg/"ReaderActivity.java"
edit(r,"markButton, widthLabel;", "markButton, eraserButton, widthLabel;")
edit(r,"isPng = false, highlighting = false;", "isPng = false, highlighting = false, erasing = false;")
edit(r,"        documentView.setListener(this);",
"""        documentView.setListener(this);
        AnnotationStore.pruneExpired(this);""")
edit(r,"            highlighting = !highlighting;\n            documentView.setHighlightMode(highlighting);",
"""            highlighting = !highlighting;
            erasing=false;
            documentView.setEraserMode(false);
            eraserButton.setBackground(background(NAVY,10));
            documentView.setHighlightMode(highlighting);""")
edit(r,'        TextView help = new TextView(this);',
"""        eraserButton=button("⌫",NAVY,Color.WHITE,48);
        eraserButton.setTextSize(25);
        eraserButton.setContentDescription("Eraser: remove highlights only");
        dock.addView(eraserButton,item(48,54,5));
        eraserButton.setOnClickListener(v->{
            erasing=!erasing;
            highlighting=false;
            documentView.setHighlightMode(false);
            documentView.setEraserMode(erasing);
            eraserButton.setBackground(background(erasing?BLUE:NAVY,10));
            markButton.setBackground(background(NAVY,10));
            markButton.setContentDescription("Enable highlighter");
            markerSettings.setVisibility(View.GONE);
        });

        TextView help = new TextView(this);""")
edit(r, "Pinch to zoom  •  Drag to move  •  ✎ to highlight", "Pinch to zoom • ✎ highlight • ⌫ erase marks • drafts 4 days")
print("Patched version 1.2 eraser and 4-day expiry")
