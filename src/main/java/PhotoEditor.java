import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Simple photo editor (Java 11+, Swing only).
 * Features: open / drag-and-drop, crop, resize (pixels or percent),
 * background -> transparent / white / black, save as PNG / JPEG / BMP / GIF.
 *
 * Compile: javac PhotoEditor.java
 * Run: java PhotoEditor [image-file]
 */
public class PhotoEditor extends JFrame {

    private static final String APP_NAME = "Photo Editor";
    private static final int MAX_UNDO = 10;
    private static final int MAX_SIDE = 30000;
    private static final long MAX_PIXELS = 120_000_000L;
    private static final Color CANVAS_BG = new Color(52, 54, 58);

    /**
     * {description, extension, extension...}; the first extension is the default.
     */
    private static final String[][] FORMATS = {
            { "PNG image (*.png)", "png" },
            { "JPEG image (*.jpg, *.jpeg)", "jpg", "jpeg" },
            { "BMP image (*.bmp)", "bmp" },
            { "GIF image (*.gif)", "gif" }
    };

    private enum Tool {
        CROP, BACKGROUND
    }

    private final ImageCanvas canvas = new ImageCanvas();
    private final JScrollPane scroll;
    private final JLabel infoLabel = new JLabel(" ");
    private final JLabel zoomLabel = new JLabel(" ");
    private final CardLayout optionCards = new CardLayout();
    private final JPanel optionsPanel = new JPanel(optionCards);
    private final JToggleButton cropToggle = new JToggleButton("Crop");
    private final JToggleButton bgToggle = new JToggleButton("Background");
    private final JRadioButton rbTransparent = new JRadioButton("Transparent", true);
    private final JRadioButton rbWhite = new JRadioButton("White");
    private final JRadioButton rbBlack = new JRadioButton("Black");
    private final JSlider tolSlider = new JSlider(0, 100, 20);
    private final JLabel tolValue = new JLabel("20%");
    private final JCheckBox contiguousBox = new JCheckBox("Connected area only", true);

    private Action openAction, saveAction, undoAction, cropAction, clearSelAction, resizeAction,
            zoomInAction, zoomOutAction, fitAction, actualAction;

    private BufferedImage image;
    private File currentFile;
    private boolean dirty;
    private final Deque<BufferedImage> undoStack = new ArrayDeque<>();
    private Tool tool = Tool.CROP;
    private String message = "";

    // ------------------------------------------------------------------ setup

    public PhotoEditor() {
        super(APP_NAME);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });

        createActions();
        setJMenuBar(buildMenuBar());

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(buildToolBar());
        top.add(buildOptionsBar());

        scroll = new JScrollPane(canvas);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(CANVAS_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                canvas.applyFit();
            }
        });

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        JRootPane rp = getRootPane();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "applyCrop");
        rp.getActionMap().put("applyCrop", cropAction);
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearSel");
        rp.getActionMap().put("clearSel", clearSelAction);

        TransferHandler dnd = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport s) {
                if (!canImport(s))
                    return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) s.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty())
                        return false;
                    File f = files.get(0);
                    SwingUtilities.invokeLater(() -> openFile(f));
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        };
        ((JComponent) getContentPane()).setTransferHandler(dnd);
        canvas.setTransferHandler(dnd);

        setSize(1100, 760);
        setMinimumSize(new Dimension(760, 480));
        setLocationRelativeTo(null);
        updateUi();
    }

    private static KeyStroke ks(int key, int extraMods) {
        return KeyStroke.getKeyStroke(key,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | extraMods);
    }

    private Action action(String name, String tip, KeyStroke ks, Runnable r) {
        AbstractAction a = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
        if (tip != null)
            a.putValue(Action.SHORT_DESCRIPTION, tip);
        if (ks != null)
            a.putValue(Action.ACCELERATOR_KEY, ks);
        return a;
    }

    private void createActions() {
        openAction = action("Open…", "Open an image (Ctrl+O)", ks(KeyEvent.VK_O, 0), this::openDialog);
        saveAction = action("Save…", "Save the image (Ctrl+S)", ks(KeyEvent.VK_S, 0), () -> saveAs());
        undoAction = action("Undo", "Undo last change (Ctrl+Z)", ks(KeyEvent.VK_Z, 0), this::undo);
        cropAction = action("Crop to Selection", "Crop to the selected area (Enter)", null, this::cropToSelection);
        clearSelAction = action("Clear Selection", "Remove the selection (Esc)", null, canvas::clearSelection);
        resizeAction = action("Resize…", "Change image size / resolution (Ctrl+R)", ks(KeyEvent.VK_R, 0),
                this::showResizeDialog);
        zoomInAction = action("Zoom In", "Zoom in (Ctrl +)", ks(KeyEvent.VK_EQUALS, 0), () -> canvas.zoomBy(1.25));
        zoomOutAction = action("Zoom Out", "Zoom out (Ctrl -)", ks(KeyEvent.VK_MINUS, 0), () -> canvas.zoomBy(0.8));
        fitAction = action("Fit to Window", "Fit image to the window (Ctrl+0)", ks(KeyEvent.VK_0, 0),
                canvas::fitToWindow);
        actualAction = action("Actual Size", "Show at 100% (Ctrl+1)", ks(KeyEvent.VK_1, 0), canvas::actualSize);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(new JMenuItem(openAction));
        file.add(new JMenuItem(saveAction));
        file.addSeparator();
        file.add(new JMenuItem(action("Exit", null, null, this::exit)));
        JMenu edit = new JMenu("Edit");
        edit.add(new JMenuItem(undoAction));
        edit.addSeparator();
        edit.add(new JMenuItem(cropAction));
        edit.add(new JMenuItem(clearSelAction));
        edit.addSeparator();
        edit.add(new JMenuItem(resizeAction));
        JMenu view = new JMenu("View");
        view.add(new JMenuItem(zoomInAction));
        view.add(new JMenuItem(zoomOutAction));
        view.add(new JMenuItem(fitAction));
        view.add(new JMenuItem(actualAction));
        mb.add(file);
        mb.add(edit);
        mb.add(view);
        return mb;
    }

    private JButton toolButton(Action a) {
        JButton b = new JButton(a);
        b.setFocusable(false);
        return b;
    }

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(new EmptyBorder(4, 6, 4, 6));
        tb.add(toolButton(openAction));
        tb.add(toolButton(saveAction));
        tb.addSeparator();
        tb.add(toolButton(undoAction));
        tb.addSeparator();

        cropToggle.setToolTipText("Select an area and crop it");
        bgToggle.setToolTipText("Make the background transparent, white or black");
        cropToggle.setFocusable(false);
        bgToggle.setFocusable(false);
        ButtonGroup g = new ButtonGroup();
        g.add(cropToggle);
        g.add(bgToggle);
        cropToggle.setSelected(true);
        cropToggle.addActionListener(e -> setTool(Tool.CROP));
        bgToggle.addActionListener(e -> setTool(Tool.BACKGROUND));
        tb.add(cropToggle);
        tb.add(bgToggle);
        tb.add(toolButton(resizeAction));
        tb.addSeparator();

        JButton fit = toolButton(fitAction);
        fit.setText("Fit");
        JButton actual = toolButton(actualAction);
        actual.setText("100%");
        JButton zin = toolButton(zoomInAction);
        zin.setText("+");
        JButton zout = toolButton(zoomOutAction);
        zout.setText("−");
        tb.add(fit);
        tb.add(actual);
        tb.add(zout);
        tb.add(zin);
        return tb;
    }

    private JComponent buildOptionsBar() {
        JPanel crop = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        crop.add(new JLabel(
                "Drag on the image to select an area. Drag inside to move it, drag edges or corners to resize."));
        JButton apply = toolButton(cropAction);
        crop.add(apply);
        crop.add(toolButton(clearSelAction));

        JPanel bg = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        bg.add(new JLabel("Click the background to make it:"));
        ButtonGroup g = new ButtonGroup();
        for (JRadioButton rb : new JRadioButton[] { rbTransparent, rbWhite, rbBlack }) {
            g.add(rb);
            rb.setFocusable(false);
            bg.add(rb);
        }
        bg.add(new JLabel("   Tolerance:"));
        tolSlider.setPreferredSize(new Dimension(140, tolSlider.getPreferredSize().height));
        tolSlider.setFocusable(false);
        tolSlider.setToolTipText("How different a color may be from the clicked one and still be replaced");
        tolSlider.addChangeListener(e -> tolValue.setText(tolSlider.getValue() + "%"));
        bg.add(tolSlider);
        bg.add(tolValue);
        contiguousBox.setFocusable(false);
        contiguousBox.setToolTipText("Off: replace matching colors everywhere in the image");
        bg.add(contiguousBox);

        optionsPanel.add(crop, Tool.CROP.name());
        optionsPanel.add(bg, Tool.BACKGROUND.name());
        optionsPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0,
                UIManager.getColor("Separator.foreground") != null
                        ? UIManager.getColor("Separator.foreground")
                        : Color.LIGHT_GRAY));
        return optionsPanel;
    }

    private JComponent buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(3, 8, 3, 8));
        p.add(infoLabel, BorderLayout.CENTER);
        p.add(zoomLabel, BorderLayout.EAST);
        return p;
    }

    // ------------------------------------------------------------- UI state

    private void setTool(Tool t) {
        tool = t;
        canvas.clearSelection();
        optionCards.show(optionsPanel, t.name());
        canvas.updateCursor();
        message = "";
        updateUi();
        canvas.repaint();
    }

    private void updateUi() {
        boolean has = image != null;
        saveAction.setEnabled(has);
        resizeAction.setEnabled(has);
        undoAction.setEnabled(!undoStack.isEmpty());
        cropAction.setEnabled(has && tool == Tool.CROP && canvas.hasSelection());
        clearSelAction.setEnabled(has && tool == Tool.CROP && canvas.hasSelection());
        zoomInAction.setEnabled(has);
        zoomOutAction.setEnabled(has);
        fitAction.setEnabled(has);
        actualAction.setEnabled(has);
        cropToggle.setEnabled(has);
        bgToggle.setEnabled(has);

        String name = currentFile != null ? currentFile.getName() : (has ? "Untitled" : "");
        setTitle((has ? name + (dirty ? " *" : "") + " – " : "") + APP_NAME);

        if (!has) {
            infoLabel.setText("Open an image to start");
            zoomLabel.setText(" ");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("   •   ").append(image.getWidth()).append(" × ")
                .append(image.getHeight()).append(" px");
        Rectangle sel = canvas.selectionRect();
        if (tool == Tool.CROP && sel != null) {
            sb.append("   •   Selection: ").append(sel.width).append(" × ").append(sel.height).append(" px");
        }
        if (!message.isEmpty())
            sb.append("   •   ").append(message);
        infoLabel.setText(sb.toString());
        zoomLabel.setText(Math.round(canvas.zoom * 100) + "%");
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, APP_NAME, JOptionPane.ERROR_MESSAGE);
    }

    // ----------------------------------------------------------------- open

    private void openDialog() {
        if (!confirmDiscard())
            return;
        JFileChooser fc = new JFileChooser(currentFile != null ? currentFile.getParentFile() : null);
        fc.setDialogTitle("Open Image");
        fc.setAcceptAllFileFilterUsed(false);
        fc.addChoosableFileFilter(new FileNameExtensionFilter(
                "Images (png, jpg, jpeg, bmp, gif, wbmp)", "png", "jpg", "jpeg", "bmp", "gif", "wbmp"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadFile(fc.getSelectedFile());
        }
    }

    /** Opens a file after asking whether to discard unsaved changes. */
    void openFile(File f) {
        if (!confirmDiscard())
            return;
        loadFile(f);
    }

    private void loadFile(File f) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                error("This file is not a supported image:\n" + f.getName());
                return;
            }
            image = ImageOps.toArgb(img);
            currentFile = f;
            undoStack.clear();
            dirty = false;
            message = "";
            canvas.imageChanged(true);
            updateUi();
        } catch (IOException ex) {
            error("Could not open the file:\n" + ex.getMessage());
        } catch (OutOfMemoryError oom) {
            error("The image is too large to open.");
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private boolean confirmDiscard() {
        if (!dirty || image == null)
            return true;
        Object[] options = { "Save", "Discard", "Cancel" };
        int r = JOptionPane.showOptionDialog(this, "Save changes to the image before continuing?",
                APP_NAME, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (r == 0)
            return saveAs();
        return r == 1;
    }

    private void exit() {
        if (confirmDiscard()) {
            dispose();
            System.exit(0);
        }
    }

    // ----------------------------------------------------------------- save

    private static int formatIndex(String fileName) {
        String n = fileName.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0)
            return -1;
        String ext = n.substring(dot + 1);
        for (int i = 0; i < FORMATS.length; i++) {
            for (int j = 1; j < FORMATS[i].length; j++) {
                if (FORMATS[i][j].equals(ext))
                    return i;
            }
        }
        return -1;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private boolean saveAs() {
        if (image == null)
            return false;
        JFileChooser fc = new JFileChooser(currentFile != null ? currentFile.getParentFile() : null);
        fc.setDialogTitle("Save Image");
        fc.setAcceptAllFileFilterUsed(false);
        FileNameExtensionFilter[] filters = new FileNameExtensionFilter[FORMATS.length];
        for (int i = 0; i < FORMATS.length; i++) {
            String[] exts = new String[FORMATS[i].length - 1];
            System.arraycopy(FORMATS[i], 1, exts, 0, exts.length);
            filters[i] = new FileNameExtensionFilter(FORMATS[i][0], exts);
            fc.addChoosableFileFilter(filters[i]);
        }
        int idx = currentFile != null ? formatIndex(currentFile.getName()) : 0;
        if (idx < 0)
            idx = 0;
        fc.setFileFilter(filters[idx]);
        String base = currentFile != null ? stripExt(currentFile.getName()) : "image";
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), base + "." + FORMATS[idx][1]));
        fc.addPropertyChangeListener(JFileChooser.FILE_FILTER_CHANGED_PROPERTY, ev -> {
            File sf = fc.getSelectedFile();
            if (sf != null && fc.getFileFilter() instanceof FileNameExtensionFilter) {
                String ext = ((FileNameExtensionFilter) fc.getFileFilter()).getExtensions()[0];
                fc.setSelectedFile(new File(fc.getCurrentDirectory(), stripExt(sf.getName()) + "." + ext));
            }
        });

        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
            return false;

        File f = fc.getSelectedFile();
        int fi = formatIndex(f.getName());
        if (fi < 0) {
            fi = 0;
            for (int i = 0; i < filters.length; i++) {
                if (filters[i] == fc.getFileFilter())
                    fi = i;
            }
            f = new File(f.getParentFile(), f.getName() + "." + FORMATS[fi][1]);
        }
        String fmt = FORMATS[fi][1];

        if (f.exists()) {
            int r = JOptionPane.showConfirmDialog(this, f.getName() + " already exists.\nReplace it?",
                    "Confirm Save", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION)
                return false;
        }
        boolean noAlpha = fmt.equals("jpg") || fmt.equals("bmp");
        if (noAlpha && ImageOps.hasTransparency(image)) {
            int r = JOptionPane.showConfirmDialog(this,
                    fmt.toUpperCase() + " does not support transparency.\nTransparent areas will be saved as white.",
                    "Transparency", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (r != JOptionPane.OK_OPTION)
                return false;
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            ImageOps.write(image, f, fmt);
            currentFile = f;
            dirty = false;
            message = "Saved";
            updateUi();
            return true;
        } catch (IOException | RuntimeException ex) {
            error("Could not save the image:\n" + ex.getMessage());
            return false;
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    // ----------------------------------------------------------------- edits

    private void commit(BufferedImage newImage, boolean refit) {
        undoStack.addFirst(image);
        while (undoStack.size() > MAX_UNDO)
            undoStack.removeLast();
        image = newImage;
        dirty = true;
        canvas.imageChanged(refit);
        updateUi();
    }

    private void undo() {
        if (undoStack.isEmpty())
            return;
        BufferedImage prev = undoStack.pollFirst();
        boolean sameSize = prev.getWidth() == image.getWidth() && prev.getHeight() == image.getHeight();
        image = prev;
        dirty = true;
        message = "";
        canvas.imageChanged(!sameSize);
        updateUi();
    }

    private void cropToSelection() {
        Rectangle r = canvas.selectionRect();
        if (image == null || r == null)
            return;
        r = r.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        if (r.width < 1 || r.height < 1)
            return;
        message = "Cropped";
        commit(ImageOps.crop(image, r), true);
    }

    private void applyBackgroundChange(int x, int y) {
        int fill = rbWhite.isSelected() ? 0xFFFFFFFF : rbBlack.isSelected() ? 0xFF000000 : 0x00000000;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            ImageOps.BgResult res = ImageOps.replaceBackground(image, x, y,
                    tolSlider.getValue(), contiguousBox.isSelected(), fill);
            if (res == null) {
                message = "Nothing to change at that point";
                updateUi();
                return;
            }
            message = "Background changed";
            commit(res.image, false);
        } catch (OutOfMemoryError oom) {
            error("Not enough memory for this operation.");
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private void showResizeDialog() {
        if (image == null)
            return;
        final int ow = image.getWidth(), oh = image.getHeight();
        JDialog dlg = new JDialog(this, "Resize Image", true);

        JSpinner sw = new JSpinner(new SpinnerNumberModel(ow, 1, MAX_SIDE, 1));
        JSpinner sh = new JSpinner(new SpinnerNumberModel(oh, 1, MAX_SIDE, 1));
        JSpinner sp = new JSpinner(new SpinnerNumberModel(100.0, 0.1, 5000.0, 1.0));
        sp.setEditor(new JSpinner.NumberEditor(sp, "0.##"));
        JCheckBox keep = new JCheckBox("Keep aspect ratio", true);
        JComboBox<String> quality = new JComboBox<>(new String[] {
                "Smooth (best for photos)", "Fast (bilinear)", "Pixelated (nearest neighbor)" });

        boolean[] busy = { false };
        ChangeListener onW = e -> {
            if (busy[0])
                return;
            busy[0] = true;
            try {
                int w = (Integer) sw.getValue();
                if (keep.isSelected()) {
                    sh.setValue((int) Math.max(1, Math.min(MAX_SIDE, Math.round(w * (double) oh / ow))));
                }
                sp.setValue(Math.max(0.1, Math.min(5000.0, w * 100.0 / ow)));
            } finally {
                busy[0] = false;
            }
        };
        ChangeListener onH = e -> {
            if (busy[0])
                return;
            busy[0] = true;
            try {
                int h = (Integer) sh.getValue();
                if (keep.isSelected()) {
                    sw.setValue((int) Math.max(1, Math.min(MAX_SIDE, Math.round(h * (double) ow / oh))));
                }
                sp.setValue(Math.max(0.1, Math.min(5000.0, h * 100.0 / oh)));
            } finally {
                busy[0] = false;
            }
        };
        ChangeListener onP = e -> {
            if (busy[0])
                return;
            busy[0] = true;
            try {
                double p = ((Number) sp.getValue()).doubleValue();
                sw.setValue((int) Math.max(1, Math.min(MAX_SIDE, Math.round(ow * p / 100.0))));
                sh.setValue((int) Math.max(1, Math.min(MAX_SIDE, Math.round(oh * p / 100.0))));
            } finally {
                busy[0] = false;
            }
        };
        sw.addChangeListener(onW);
        sh.addChangeListener(onH);
        sp.addChangeListener(onP);
        keep.addActionListener(e -> {
            if (keep.isSelected())
                onW.stateChanged(null);
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(14, 16, 6, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        form.add(new JLabel("Current size: " + ow + " × " + oh + " px"), c);
        c.gridwidth = 1;
        String[] labels = { "Width:", "Height:", "Scale:" };
        JComponent[] fields = { sw, sh, sp };
        String[] units = { "px", "px", "%" };
        for (int i = 0; i < 3; i++) {
            c.gridy = i + 1;
            c.gridx = 0;
            c.fill = GridBagConstraints.NONE;
            form.add(new JLabel(labels[i]), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            fields[i].setPreferredSize(new Dimension(110, fields[i].getPreferredSize().height));
            form.add(fields[i], c);
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            form.add(new JLabel(units[i]), c);
        }
        c.gridx = 1;
        c.gridy = 4;
        c.gridwidth = 2;
        form.add(keep, c);
        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 1;
        form.add(new JLabel("Quality:"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(quality, c);

        JButton ok = new JButton("Resize");
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dlg.dispose());
        ok.addActionListener(e -> {
            int w = (Integer) sw.getValue(), h = (Integer) sh.getValue();
            if (w == ow && h == oh) {
                dlg.dispose();
                return;
            }
            if ((long) w * h > MAX_PIXELS) {
                error("The result would be too large (" + w + " × " + h + " px).");
                return;
            }
            Object hint = quality.getSelectedIndex() == 0 ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                    : quality.getSelectedIndex() == 1 ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                            : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
            dlg.dispose();
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            try {
                BufferedImage out = ImageOps.scale(image, w, h, hint);
                message = "Resized to " + w + " × " + h + " px";
                commit(out, true);
            } catch (OutOfMemoryError oom) {
                error("Not enough memory for this size.");
            } finally {
                setCursor(Cursor.getDefaultCursor());
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.setBorder(new EmptyBorder(0, 8, 4, 8));
        buttons.add(cancel);
        buttons.add(ok);

        dlg.add(form, BorderLayout.CENTER);
        dlg.add(buttons, BorderLayout.SOUTH);
        dlg.getRootPane().setDefaultButton(ok);
        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dlg.pack();
        dlg.setResizable(false);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    // ---------------------------------------------------------------- canvas

    private class ImageCanvas extends JPanel implements Scrollable {
        static final int MARGIN = 24;
        double zoom = 1.0;
        boolean fit = true;

        private boolean hasSel;
        private int sl, st, sr, sb; // selection in image coordinates
        private int dragMode; // 0 none, 1 resize, 2 move
        private boolean fL, fR, fT, fB; // edges being dragged
        private boolean hL, hR, hT, hB; // edges under the mouse
        private int moveDx, moveDy;
        private BufferedImage previewCache, previewSrc;

        ImageCanvas() {
            setBackground(CANVAS_BG);
            setOpaque(true);
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (image == null || !SwingUtilities.isLeftMouseButton(e))
                        return;
                    requestFocusInWindow();
                    if (tool == Tool.CROP)
                        pressCrop(e);
                    else
                        pressBackground(e);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    dragCrop(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    releaseCrop();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    hover(e.getPoint());
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    wheel(e);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }

        // ----- geometry
        private int dispW() {
            return Math.max(1, (int) Math.round(image.getWidth() * zoom));
        }

        private int dispH() {
            return Math.max(1, (int) Math.round(image.getHeight() * zoom));
        }

        private Point origin() {
            return new Point(Math.max(MARGIN, (getWidth() - dispW()) / 2),
                    Math.max(MARGIN, (getHeight() - dispH()) / 2));
        }

        @Override
        public Dimension getPreferredSize() {
            if (image == null)
                return new Dimension(400, 300);
            return new Dimension(dispW() + 2 * MARGIN, dispH() + 2 * MARGIN);
        }

        // ----- Scrollable
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return o == SwingConstants.VERTICAL ? r.height - 24 : r.width - 24;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport && getParent().getWidth() > getPreferredSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
        }

        // ----- state
        void imageChanged(boolean refit) {
            previewCache = null;
            previewSrc = null;
            hasSel = false;
            dragMode = 0;
            if (refit)
                fit = true;
            applyFit();
            revalidate();
            repaint();
            updateCursor();
        }

        void updateCursor() {
            setCursor(image != null ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
        }

        boolean hasSelection() {
            return hasSel && Math.abs(sr - sl) >= 1 && Math.abs(sb - st) >= 1;
        }

        Rectangle selectionRect() {
            if (!hasSelection())
                return null;
            return new Rectangle(Math.min(sl, sr), Math.min(st, sb), Math.abs(sr - sl), Math.abs(sb - st));
        }

        void clearSelection() {
            hasSel = false;
            dragMode = 0;
            repaint();
            updateUi();
        }

        private Rectangle selScreenRect() {
            Point o = origin();
            int x1 = o.x + (int) Math.round(Math.min(sl, sr) * zoom);
            int y1 = o.y + (int) Math.round(Math.min(st, sb) * zoom);
            int x2 = o.x + (int) Math.round(Math.max(sl, sr) * zoom);
            int y2 = o.y + (int) Math.round(Math.max(st, sb) * zoom);
            return new Rectangle(x1, y1, x2 - x1, y2 - y1);
        }

        // ----- zoom
        void applyFit() {
            if (!fit || image == null)
                return;
            Dimension ext = scroll.getViewport().getExtentSize();
            if (ext.width <= 2 * MARGIN + 10 || ext.height <= 2 * MARGIN + 10)
                return;
            double zw = (ext.width - 2.0 * MARGIN) / image.getWidth();
            double zh = (ext.height - 2.0 * MARGIN) / image.getHeight();
            double z = Math.max(0.01, Math.min(1.0, Math.min(zw, zh)));
            if (Math.abs(z - zoom) > 1e-9) {
                zoom = z;
                previewCache = null;
                revalidate();
                repaint();
                updateUi();
            }
            scroll.getViewport().setViewPosition(new Point(0, 0));
        }

        void fitToWindow() {
            if (image == null)
                return;
            fit = true;
            zoom = -1; // force recompute
            applyFit();
            revalidate();
            repaint();
            updateUi();
        }

        void actualSize() {
            zoomTo(1.0, viewCenter());
        }

        void zoomBy(double factor) {
            zoomTo(zoom * factor, viewCenter());
        }

        private Point viewCenter() {
            JViewport vp = scroll.getViewport();
            Point p = vp.getViewPosition();
            Dimension d = vp.getExtentSize();
            return new Point(p.x + d.width / 2, p.y + d.height / 2);
        }

        void zoomTo(double z, Point anchor) {
            if (image == null)
                return;
            z = Math.max(0.02, Math.min(32.0, z));
            Point o = origin();
            double ix = (anchor.x - o.x) / zoom, iy = (anchor.y - o.y) / zoom;
            JViewport vp = scroll.getViewport();
            Point vpPos = vp.getViewPosition();
            int offX = anchor.x - vpPos.x, offY = anchor.y - vpPos.y;
            fit = false;
            zoom = z;
            previewCache = null;
            revalidate();
            repaint();
            updateUi();
            SwingUtilities.invokeLater(() -> {
                Point o2 = origin();
                Dimension ext = vp.getExtentSize();
                int nx = (int) Math.round(o2.x + ix * zoom) - offX;
                int ny = (int) Math.round(o2.y + iy * zoom) - offY;
                nx = Math.max(0, Math.min(nx, getWidth() - ext.width));
                ny = Math.max(0, Math.min(ny, getHeight() - ext.height));
                vp.setViewPosition(new Point(nx, ny));
            });
        }

        private void wheel(MouseWheelEvent e) {
            if (image == null)
                return;
            if (e.isControlDown() || e.isMetaDown()) {
                zoomTo(zoom * Math.pow(1.1, -e.getPreciseWheelRotation()), e.getPoint());
            } else {
                JScrollBar bar = e.isShiftDown() ? scroll.getHorizontalScrollBar() : scroll.getVerticalScrollBar();
                bar.setValue(bar.getValue() + (int) Math.round(e.getPreciseWheelRotation() * 48));
            }
        }

        // ----- mouse: crop
        private int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private int hitTest(Point p) {
            hL = hR = hT = hB = false;
            if (!hasSel)
                return 0;
            Rectangle r = selScreenRect();
            int x1 = r.x, y1 = r.y, x2 = r.x + r.width, y2 = r.y + r.height;
            final int T = 7;
            boolean inX = p.x >= x1 - T && p.x <= x2 + T;
            boolean inY = p.y >= y1 - T && p.y <= y2 + T;
            if (inY) {
                boolean nl = Math.abs(p.x - x1) <= T, nr = Math.abs(p.x - x2) <= T;
                if (nl && nr) {
                    if (Math.abs(p.x - x1) <= Math.abs(p.x - x2))
                        nr = false;
                    else
                        nl = false;
                }
                hL = nl;
                hR = nr;
            }
            if (inX) {
                boolean nt = Math.abs(p.y - y1) <= T, nb = Math.abs(p.y - y2) <= T;
                if (nt && nb) {
                    if (Math.abs(p.y - y1) <= Math.abs(p.y - y2))
                        nb = false;
                    else
                        nt = false;
                }
                hT = nt;
                hB = nb;
            }
            if (hL || hR || hT || hB)
                return 1;
            if (p.x >= x1 && p.x <= x2 && p.y >= y1 && p.y <= y2)
                return 2;
            return 0;
        }

        private Cursor cursorFor(int mode) {
            if (mode == 2)
                return Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
            if (mode == 1) {
                int c;
                if (hT && hL)
                    c = Cursor.NW_RESIZE_CURSOR;
                else if (hT && hR)
                    c = Cursor.NE_RESIZE_CURSOR;
                else if (hB && hL)
                    c = Cursor.SW_RESIZE_CURSOR;
                else if (hB && hR)
                    c = Cursor.SE_RESIZE_CURSOR;
                else if (hT)
                    c = Cursor.N_RESIZE_CURSOR;
                else if (hB)
                    c = Cursor.S_RESIZE_CURSOR;
                else if (hL)
                    c = Cursor.W_RESIZE_CURSOR;
                else
                    c = Cursor.E_RESIZE_CURSOR;
                return Cursor.getPredefinedCursor(c);
            }
            return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
        }

        private void hover(Point p) {
            if (image == null)
                return;
            if (tool == Tool.CROP)
                setCursor(cursorFor(hitTest(p)));
            else
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        private void pressCrop(MouseEvent e) {
            Point o = origin();
            int iw = image.getWidth(), ih = image.getHeight();
            int mode = hitTest(e.getPoint());
            if (mode == 2 && e.getClickCount() == 2) {
                cropToSelection();
                return;
            }
            if (mode == 0) {
                int cx = clamp((int) Math.round((e.getX() - o.x) / zoom), 0, iw);
                int cy = clamp((int) Math.round((e.getY() - o.y) / zoom), 0, ih);
                hasSel = true;
                sl = sr = cx;
                st = sb = cy;
                fL = fT = false;
                fR = fB = true;
                dragMode = 1;
            } else if (mode == 1) {
                fL = hL;
                fR = hR;
                fT = hT;
                fB = hB;
                dragMode = 1;
            } else {
                normalizeSel();
                int mx = (int) Math.round((e.getX() - o.x) / zoom);
                int my = (int) Math.round((e.getY() - o.y) / zoom);
                moveDx = mx - sl;
                moveDy = my - st;
                dragMode = 2;
            }
            repaint();
            updateUi();
        }

        private void dragCrop(MouseEvent e) {
            if (image == null || dragMode == 0 || tool != Tool.CROP)
                return;
            Point o = origin();
            int iw = image.getWidth(), ih = image.getHeight();
            int rx = (int) Math.round((e.getX() - o.x) / zoom);
            int ry = (int) Math.round((e.getY() - o.y) / zoom);
            if (dragMode == 1) {
                int cx = clamp(rx, 0, iw), cy = clamp(ry, 0, ih);
                if (fL)
                    sl = cx;
                if (fR)
                    sr = cx;
                if (fT)
                    st = cy;
                if (fB)
                    sb = cy;
            } else {
                int w = sr - sl, h = sb - st;
                int nx = clamp(rx - moveDx, 0, iw - w);
                int ny = clamp(ry - moveDy, 0, ih - h);
                sl = nx;
                sr = nx + w;
                st = ny;
                sb = ny + h;
            }
            repaint();
            updateUi();
        }

        private void releaseCrop() {
            if (dragMode == 0)
                return;
            dragMode = 0;
            normalizeSel();
            if (sr - sl < 2 || sb - st < 2)
                hasSel = false;
            repaint();
            updateUi();
        }

        private void normalizeSel() {
            if (sl > sr) {
                int t = sl;
                sl = sr;
                sr = t;
            }
            if (st > sb) {
                int t = st;
                st = sb;
                sb = t;
            }
        }

        // ----- mouse: background
        private void pressBackground(MouseEvent e) {
            Point o = origin();
            int ix = (int) Math.floor((e.getX() - o.x) / zoom);
            int iy = (int) Math.floor((e.getY() - o.y) / zoom);
            if (ix < 0 || iy < 0 || ix >= image.getWidth() || iy >= image.getHeight())
                return;
            applyBackgroundChange(ix, iy);
        }

        // ----- painting
        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            try {
                if (image == null) {
                    paintHint(g);
                    return;
                }
                int dw = dispW(), dh = dispH();
                Point o = origin();
                Rectangle dest = new Rectangle(o.x, o.y, dw, dh);
                paintChecker(g, dest);

                BufferedImage view = image;
                if (dw < image.getWidth() || dh < image.getHeight()) {
                    if (previewCache == null || previewSrc != image
                            || previewCache.getWidth() != dw || previewCache.getHeight() != dh) {
                        previewCache = ImageOps.scale(image, dw, dh, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        previewSrc = image;
                    }
                    view = previewCache;
                }
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, zoom >= 2
                        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(view, o.x, o.y, dw, dh, null);
                g.setColor(new Color(0, 0, 0, 90));
                g.drawRect(o.x - 1, o.y - 1, dw + 1, dh + 1);

                if (tool == Tool.CROP && hasSel)
                    paintSelection(g, dest);
            } finally {
                g.dispose();
            }
        }

        private void paintHint(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(190, 192, 196));
            g.setFont(getFont().deriveFont(Font.PLAIN, 16f));
            String s = "Open an image (Ctrl+O) or drop an image file here";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, getHeight() / 2);
        }

        private void paintChecker(Graphics2D g, Rectangle dest) {
            Rectangle clip = g.getClipBounds();
            Rectangle vis = clip == null ? dest : clip.intersection(dest);
            if (vis.isEmpty())
                return;
            final int s = 10;
            g.setColor(Color.WHITE);
            g.fillRect(vis.x, vis.y, vis.width, vis.height);
            g.setColor(new Color(0xD2D2D2));
            int startY = ((vis.y - dest.y) / s) * s;
            int startX = ((vis.x - dest.x) / s) * s;
            for (int y = startY; y < vis.y - dest.y + vis.height; y += s) {
                for (int x = startX; x < vis.x - dest.x + vis.width; x += s) {
                    if (((x / s) + (y / s)) % 2 == 1) {
                        Rectangle cell = new Rectangle(dest.x + x, dest.y + y, s, s).intersection(vis);
                        if (!cell.isEmpty())
                            g.fillRect(cell.x, cell.y, cell.width, cell.height);
                    }
                }
            }
        }

        private void paintSelection(Graphics2D g, Rectangle dest) {
            Rectangle r = selScreenRect();
            Area dim = new Area(dest);
            dim.subtract(new Area(r));
            g.setColor(new Color(0, 0, 0, 140));
            g.fill(dim);

            g.setColor(new Color(255, 255, 255, 70));
            for (int i = 1; i <= 2; i++) {
                int gx = r.x + r.width * i / 3, gy = r.y + r.height * i / 3;
                g.drawLine(gx, r.y, gx, r.y + r.height);
                g.drawLine(r.x, gy, r.x + r.width, gy);
            }
            g.setColor(Color.WHITE);
            g.drawRect(r.x, r.y, r.width, r.height);
            g.setColor(Color.BLACK);
            g.setStroke(
                    new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 4f, 4f }, 0f));
            g.drawRect(r.x, r.y, r.width, r.height);
            g.setStroke(new BasicStroke(1f));

            int[] xs = { r.x, r.x + r.width / 2, r.x + r.width };
            int[] ys = { r.y, r.y + r.height / 2, r.y + r.height };
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    if (i == 1 && j == 1)
                        continue;
                    g.setColor(Color.WHITE);
                    g.fillRect(xs[i] - 4, ys[j] - 4, 8, 8);
                    g.setColor(Color.BLACK);
                    g.drawRect(xs[i] - 4, ys[j] - 4, 8, 8);
                }
            }

            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            String label = Math.abs(sr - sl) + " × " + Math.abs(sb - st);
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            FontMetrics fm = g.getFontMetrics();
            int lw = fm.stringWidth(label) + 12, lh = fm.getHeight() + 4;
            int lx = r.x, ly = r.y - lh - 6;
            if (ly < dest.y)
                ly = r.y + 6;
            g.setColor(new Color(0, 0, 0, 180));
            g.fillRoundRect(lx, ly, lw, lh, 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(label, lx + 6, ly + 2 + fm.getAscent());
        }
    }

    // ------------------------------------------------------------ image ops

    /**
     * Pure image operations (no UI), kept separate so they can be tested
     * headlessly.
     */
    static final class ImageOps {
        private ImageOps() {
        }

        static final class BgResult {
            final BufferedImage image;
            final int count;

            BgResult(BufferedImage image, int count) {
                this.image = image;
                this.count = count;
            }
        }

        static BufferedImage toArgb(BufferedImage src) {
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(src, 0, 0, null);
            g.dispose();
            return out;
        }

        static BufferedImage crop(BufferedImage src, Rectangle r) {
            BufferedImage out = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(src, -r.x, -r.y, null);
            g.dispose();
            return out;
        }

        /** High-quality scaling; shrinks in halving steps to avoid aliasing. */
        static BufferedImage scale(BufferedImage src, int tw, int th, Object hint) {
            boolean nearest = hint == RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
            BufferedImage cur = src;
            int cw = src.getWidth(), ch = src.getHeight();
            while (cw != tw || ch != th) {
                int nw = (!nearest && cw > tw * 2) ? cw / 2 : tw;
                int nh = (!nearest && ch > th * 2) ? ch / 2 : th;
                boolean last = nw == tw && nh == th;
                BufferedImage step = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = step.createGraphics();
                g.setComposite(AlphaComposite.Src);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        last ? hint : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(cur, 0, 0, nw, nh, null);
                g.dispose();
                cur = step;
                cw = nw;
                ch = nh;
            }
            return cur == src ? toArgb(src) : cur;
        }

        private static int norm(int argb) {
            return (argb >>> 24) == 0 ? 0 : argb;
        }

        /**
         * Replaces the region whose color is similar to the pixel at (sx, sy) with
         * {@code fill}
         * (ARGB; 0 = transparent). Returns null when nothing would change.
         */
        static BgResult replaceBackground(BufferedImage src, int sx, int sy, int tolerancePercent,
                boolean contiguous, int fill) {
            int w = src.getWidth(), h = src.getHeight();
            int[] px = src.getRGB(0, 0, w, h, null, 0, w);
            int seed = norm(px[sy * w + sx]);
            if (seed == norm(fill))
                return null;
            double thr = tolerancePercent / 100.0 * 510.0;
            long thr2 = (long) (thr * thr);
            int count = 0;

            if (!contiguous) {
                for (int i = 0; i < px.length; i++) {
                    if (dist2(norm(px[i]), seed) <= thr2) {
                        px[i] = fill;
                        count++;
                    }
                }
            } else {
                BitSet visited = new BitSet(w * h);
                int[] stack = new int[1024];
                int sp = 0;
                int start = sy * w + sx;
                visited.set(start);
                stack[sp++] = start;
                while (sp > 0) {
                    int p = stack[--sp];
                    px[p] = fill;
                    count++;
                    int x = p % w, y = p / w;
                    for (int d = 0; d < 4; d++) {
                        int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                        int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h)
                            continue;
                        int np = ny * w + nx;
                        if (visited.get(np))
                            continue;
                        if (dist2(norm(px[np]), seed) <= thr2) {
                            visited.set(np);
                            if (sp == stack.length)
                                stack = java.util.Arrays.copyOf(stack, sp * 2);
                            stack[sp++] = np;
                        }
                    }
                }
            }
            if (count == 0)
                return null;
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, w, h, px, 0, w);
            return new BgResult(out, count);
        }

        private static long dist2(int a, int b) {
            long da = (a >>> 24) - (b >>> 24);
            long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
            long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
            long db = (a & 0xFF) - (b & 0xFF);
            return da * da + dr * dr + dg * dg + db * db;
        }

        static boolean hasTransparency(BufferedImage img) {
            if (!img.getColorModel().hasAlpha())
                return false;
            int w = img.getWidth();
            int[] row = new int[w];
            for (int y = 0; y < img.getHeight(); y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int v : row)
                    if ((v >>> 24) != 0xFF)
                        return true;
            }
            return false;
        }

        static BufferedImage flatten(BufferedImage src, Color bg) {
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(bg);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, 0, 0, null);
            g.dispose();
            return out;
        }

        static void write(BufferedImage img, File f, String fmt) throws IOException {
            BufferedImage out = img;
            boolean keepAlpha = (fmt.equals("png") || fmt.equals("gif")) && hasTransparency(img);
            if (!keepAlpha)
                out = flatten(img, Color.WHITE);

            if (fmt.equals("jpg") || fmt.equals("jpeg")) {
                Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
                if (!it.hasNext())
                    throw new IOException("No JPEG writer available");
                ImageWriter w = it.next();
                ImageWriteParam p = w.getDefaultWriteParam();
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(0.92f);
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(f)) {
                    if (ios == null)
                        throw new IOException("Cannot write to " + f);
                    w.setOutput(ios);
                    w.write(null, new IIOImage(out, null, null), p);
                } finally {
                    w.dispose();
                }
            } else if (!ImageIO.write(out, fmt, f)) {
                throw new IOException("No writer available for format " + fmt);
            }
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to the default look and feel
            }
            PhotoEditor ed = new PhotoEditor();
            ed.setVisible(true);
            if (args.length > 0)
                ed.loadFile(new File(args[0]));
        });
    }
}