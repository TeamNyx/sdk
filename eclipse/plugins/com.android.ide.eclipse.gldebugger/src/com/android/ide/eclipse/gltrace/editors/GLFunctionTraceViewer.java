/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ide.eclipse.gltrace.editors;

import com.android.ddmuilib.FindDialog;
import com.android.ddmuilib.AbstractBufferFindTarget;
import com.android.ide.eclipse.gltrace.GLProtoBuf.GLMessage.Function;
import com.android.ide.eclipse.gltrace.SwtUtils;
import com.android.ide.eclipse.gltrace.TraceFileParserTask;
import com.android.ide.eclipse.gltrace.editors.DurationMinimap.ICallSelectionListener;
import com.android.ide.eclipse.gltrace.editors.GLCallGroups.GLCallNode;
import com.android.ide.eclipse.gltrace.model.GLCall;
import com.android.ide.eclipse.gltrace.model.GLFrame;
import com.android.ide.eclipse.gltrace.model.GLTrace;
import com.android.ide.eclipse.gltrace.views.FrameSummaryViewPage;
import com.android.ide.eclipse.gltrace.views.detail.DetailsPage;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.part.EditorPart;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display OpenGL function trace in a tabular view. */
public class GLFunctionTraceViewer extends EditorPart implements ISelectionProvider {
    public static final String ID = "com.android.ide.eclipse.gltrace.GLFunctionTrace"; //$NON-NLS-1$

    private static final String DEFAULT_FILTER_MESSAGE = "Filter list of OpenGL calls. Accepts Java regexes.";
    private static final String NEWLINE = System.getProperty("line.separator"); //$NON-NLS-1$

    /** Width of thumbnail images of the framebuffer. */
    private static final int THUMBNAIL_WIDTH = 50;

    /** Height of thumbnail images of the framebuffer. */
    private static final int THUMBNAIL_HEIGHT = 50;

    private String mFilePath;
    private Scale mFrameSelectionScale;
    private Spinner mFrameSelectionSpinner;

    private GLTrace mTrace;

    private TreeViewer mFrameTreeViewer;
    private List<GLCallNode> mTreeViewerNodes;

    private Text mFilterText;
    private GLCallFilter mGLCallFilter;

    private Color mGldrawTextColor;
    private Color mGlCallErrorColor;

    // Currently displayed frame's start and end call indices.
    private int mCallStartIndex;
    private int mCallEndIndex;

    private DurationMinimap mDurationMinimap;
    private ScrollBar mVerticalScrollBar;

    private Combo mContextSwitchCombo;
    private boolean mShowContextSwitcher;
    private int mCurrentlyDisplayedContext = -1;

    private FrameSummaryViewPage mFrameSummaryViewPage;

    public GLFunctionTraceViewer() {
        mGldrawTextColor = Display.getDefault().getSystemColor(SWT.COLOR_BLUE);
        mGlCallErrorColor = Display.getDefault().getSystemColor(SWT.COLOR_RED);
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
    }

    @Override
    public void doSaveAs() {
    }

    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException {
        // we use a IURIEditorInput to allow opening files not within the workspace
        if (!(input instanceof IURIEditorInput)) {
            throw new PartInitException("GL Function Trace View: unsupported input type.");
        }

        setSite(site);
        setInput(input);
        mFilePath = ((IURIEditorInput) input).getURI().getPath();

        // set the editor part name to be the name of the file.
        File f = new File(mFilePath);
        setPartName(f.getName());
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    @Override
    public void createPartControl(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(1, false));
        GridData gd = new GridData(GridData.FILL_BOTH);
        c.setLayoutData(gd);

        ProgressMonitorDialog dlg = new ProgressMonitorDialog(parent.getShell());
        TraceFileParserTask parser = new TraceFileParserTask(mFilePath, parent.getDisplay(),
                THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        try {
            dlg.run(true, true, parser);
        } catch (InvocationTargetException e) {
            // exception while parsing, display error to user
            MessageDialog.openError(parent.getShell(),
                    "Error parsing OpenGL Trace File",
                    e.getCause().getMessage());
            return;
        } catch (InterruptedException e) {
            // operation canceled by user, just return
            return;
        }

        mTrace = parser.getTrace();
        if (mTrace == null) {
            return;
        }

        mShowContextSwitcher = mTrace.getContexts().size() > 1;

        createFrameSelectionControls(c);
        createFilterBar(c);
        createFrameTraceView(c);

        getSite().setSelectionProvider(mFrameTreeViewer);

        Display.getDefault().asyncExec(new Runnable() {
            @Override
            public void run() {
                refreshUI();
            }
        });

        IActionBars actionBars = getEditorSite().getActionBars();
        actionBars.setGlobalActionHandler(ActionFactory.COPY.getId(),
                new Action("Copy") {
            @Override
            public void run() {
                copySelectionToClipboard();
            }
        });

        actionBars.setGlobalActionHandler(ActionFactory.SELECT_ALL.getId(),
                new Action("Select All") {
            @Override
            public void run() {
                selectAll();
            }
        });

        actionBars.setGlobalActionHandler(ActionFactory.FIND.getId(),
                new Action("Find") {
            @Override
            public void run() {
                showFindDialog();
            }
        });
    }

    private void refreshUI() {
        int nFrames = 0;

        nFrames = mTrace.getFrames().size();
        setFrameCount(nFrames);
        selectFrame(1);
    }

    private void createFrameSelectionControls(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(3, false));
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        c.setLayoutData(gd);

        Label l = new Label(c, SWT.NONE);
        l.setText("Select Frame:");

        mFrameSelectionScale = new Scale(c, SWT.HORIZONTAL);
        mFrameSelectionScale.setMinimum(1);
        mFrameSelectionScale.setMaximum(1);
        mFrameSelectionScale.setSelection(0);
        gd = new GridData(GridData.FILL_HORIZONTAL);
        mFrameSelectionScale.setLayoutData(gd);

        mFrameSelectionScale.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int selectedFrame = mFrameSelectionScale.getSelection();
                mFrameSelectionSpinner.setSelection(selectedFrame);
                selectFrame(selectedFrame);
            }
        });

        mFrameSelectionSpinner = new Spinner(c, SWT.BORDER);
        gd = new GridData();
        // width to hold atleast 6 digits
        gd.widthHint = SwtUtils.getFontWidth(mFrameSelectionSpinner) * 6;
        mFrameSelectionSpinner.setLayoutData(gd);

        mFrameSelectionSpinner.setMinimum(1);
        mFrameSelectionSpinner.setMaximum(1);
        mFrameSelectionSpinner.setSelection(0);
        mFrameSelectionSpinner.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                // Disable spinner until all necessary action is complete.
                // This seems to be necessary (atleast on Linux) for the spinner to not get
                // stuck in a pressed state if it is pressed for more than a few seconds
                // continuously.
                mFrameSelectionSpinner.setEnabled(false);

                int selectedFrame = mFrameSelectionSpinner.getSelection();
                mFrameSelectionScale.setSelection(selectedFrame);
                selectFrame(selectedFrame);

                // re-enable spinner
                mFrameSelectionSpinner.setEnabled(true);
            }
        });
    }

    private void setFrameCount(int nFrames) {
        mFrameSelectionScale.setMaximum(nFrames);
        mFrameSelectionSpinner.setMaximum(nFrames);
    }

    private void selectFrame(int selectedFrame) {
        mFrameSelectionScale.setSelection(selectedFrame);
        mFrameSelectionSpinner.setSelection(selectedFrame);

        GLFrame f = mTrace.getFrame(selectedFrame - 1);
        mCallStartIndex = f.getStartIndex();
        mCallEndIndex = f.getEndIndex();

        // update tree view in the editor
        refreshTree(mCallStartIndex, mCallEndIndex, mCurrentlyDisplayedContext);

        // update minimap view
        mDurationMinimap.setCallRangeForCurrentFrame(mCallStartIndex, mCallEndIndex);

        // update the frame summary view
        if (mFrameSummaryViewPage != null) {
            mFrameSummaryViewPage.setSelectedFrame(selectedFrame - 1);
        }
    }

    /**
     * Show only calls from the given context
     * @param context context id whose calls should be displayed. Illegal values will result in
     *                calls from all contexts being displayed.
     */
    private void selectContext(int context) {
        if (mCurrentlyDisplayedContext == context) {
            return;
        }

        mCurrentlyDisplayedContext = context;
        refreshTree(mCallStartIndex, mCallEndIndex, mCurrentlyDisplayedContext);
    }

    private void refreshTree(int startCallIndex, int endCallIndex, int contextToDisplay) {
        mTreeViewerNodes = GLCallGroups.constructCallHierarchy(mTrace,
                startCallIndex, endCallIndex,
                contextToDisplay);
        mFrameTreeViewer.setInput(mTreeViewerNodes);
        mFrameTreeViewer.refresh();
        mFrameTreeViewer.expandAll();
    }

    private void createFilterBar(Composite parent) {
        int numColumns = mShowContextSwitcher ? 3 : 2;

        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(numColumns, false));
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        c.setLayoutData(gd);

        Label l = new Label(c, SWT.NONE);
        l.setText("Filter:");

        mFilterText = new Text(c, SWT.BORDER | SWT.ICON_SEARCH | SWT.SEARCH | SWT.ICON_CANCEL);
        mFilterText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        mFilterText.setMessage(DEFAULT_FILTER_MESSAGE);
        mFilterText.addModifyListener(new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {
                updateAppliedFilters();
            }
        });

        if (mShowContextSwitcher) {
            mContextSwitchCombo = new Combo(c, SWT.BORDER | SWT.READ_ONLY);

            // Setup the combo such that "All Contexts" is the first item,
            // and then we have an item for each context.
            mContextSwitchCombo.add("All Contexts");
            mContextSwitchCombo.select(0);
            mCurrentlyDisplayedContext = -1; // showing all contexts
            for (int i = 0; i < mTrace.getContexts().size(); i++) {
                mContextSwitchCombo.add("Context " + i);
            }

            mContextSwitchCombo.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    selectContext(mContextSwitchCombo.getSelectionIndex() - 1);
                }
            });
        } else {
            mCurrentlyDisplayedContext = 0;
        }
    }

    private void updateAppliedFilters() {
        mGLCallFilter.setFilters(mFilterText.getText().trim());
        mFrameTreeViewer.refresh();
    }

    private void createFrameTraceView(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(2, false));
        GridData gd = new GridData(GridData.FILL_BOTH);
        c.setLayoutData(gd);

        final Tree tree = new Tree(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        gd = new GridData(GridData.FILL_BOTH);
        tree.setLayoutData(gd);
        tree.setLinesVisible(true);
        tree.setHeaderVisible(true);

        mFrameTreeViewer = new TreeViewer(tree);
        CellLabelProvider labelProvider = new GLFrameLabelProvider();

        // column showing the GL context id
        TreeViewerColumn tvc = new TreeViewerColumn(mFrameTreeViewer, SWT.NONE);
        tvc.setLabelProvider(labelProvider);
        TreeColumn column = tvc.getColumn();
        column.setText("Function");
        column.setWidth(500);

        // column showing the GL function duration (wall clock time)
        tvc = new TreeViewerColumn(mFrameTreeViewer, SWT.NONE);
        tvc.setLabelProvider(labelProvider);
        column = tvc.getColumn();
        column.setText("Wall Time (ns)");
        column.setWidth(150);
        column.setAlignment(SWT.RIGHT);

        // column showing the GL function duration (thread time)
        tvc = new TreeViewerColumn(mFrameTreeViewer, SWT.NONE);
        tvc.setLabelProvider(labelProvider);
        column = tvc.getColumn();
        column.setText("Thread Time (ns)");
        column.setWidth(150);
        column.setAlignment(SWT.RIGHT);

        mFrameTreeViewer.setContentProvider(new GLFrameContentProvider());

        mGLCallFilter = new GLCallFilter();
        mFrameTreeViewer.addFilter(mGLCallFilter);

        // when the control is resized, give all the additional space
        // to the function name column.
        tree.addControlListener(new ControlAdapter() {
            @Override
            public void controlResized(ControlEvent e) {
                int w = mFrameTreeViewer.getTree().getClientArea().width;
                if (w > 200) {
                    mFrameTreeViewer.getTree().getColumn(2).setWidth(100);
                    mFrameTreeViewer.getTree().getColumn(1).setWidth(100);
                    mFrameTreeViewer.getTree().getColumn(0).setWidth(w - 200);
                }
            }
        });

        mDurationMinimap = new DurationMinimap(c, mTrace);
        gd = new GridData(GridData.FILL_VERTICAL);
        gd.widthHint = gd.minimumWidth = mDurationMinimap.getMinimumWidth();
        mDurationMinimap.setLayoutData(gd);
        mDurationMinimap.addCallSelectionListener(new ICallSelectionListener() {
            @Override
            public void callSelected(int selectedCallIndex) {
                if (selectedCallIndex > 0 && selectedCallIndex < mTreeViewerNodes.size()) {
                    TreeItem item = tree.getItem(selectedCallIndex);
                    tree.select(item);
                    tree.setTopItem(item);
                }
            }
        });

        mVerticalScrollBar = tree.getVerticalBar();
        mVerticalScrollBar.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateVisibleRange();
            }
        });
    }

    private void updateVisibleRange() {
        int visibleCallTopIndex = mCallStartIndex;
        int visibleCallBottomIndex = mCallEndIndex;

        if (mVerticalScrollBar.isEnabled()) {
            int selection = mVerticalScrollBar.getSelection();
            int thumb = mVerticalScrollBar.getThumb();
            int max = mVerticalScrollBar.getMaximum();

            // from the scrollbar values, compute the visible fraction
            double top = (double) selection / max;
            double bottom = (double) (selection + thumb) / max;

            // map the fraction to the call indices
            int range = mCallEndIndex - mCallStartIndex;
            visibleCallTopIndex = mCallStartIndex + (int) Math.floor(range * top);
            visibleCallBottomIndex = mCallStartIndex + (int) Math.ceil(range * bottom);
        }

        mDurationMinimap.setVisibleCallRange(visibleCallTopIndex, visibleCallBottomIndex);
    }

    @Override
    public void setFocus() {
        mFrameTreeViewer.getTree().setFocus();
    }

    private static class GLFrameContentProvider implements ITreeContentProvider {
        @Override
        public void dispose() {
        }

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        }

        @Override
        public Object[] getElements(Object inputElement) {
            return getChildren(inputElement);
        }

        @Override
        public Object[] getChildren(Object parentElement) {
            if (parentElement instanceof List<?>) {
                return ((List<?>) parentElement).toArray();
            }

            if (!(parentElement instanceof GLCallNode)) {
                return null;
            }

            GLCallNode parent = (GLCallNode) parentElement;
            if (parent.hasChildren()) {
                return parent.getChildren().toArray();
            } else {
                return new Object[0];
            }
        }

        @Override
        public Object getParent(Object element) {
            if (!(element instanceof GLCallNode)) {
                return null;
            }

            return ((GLCallNode) element).getParent();
        }

        @Override
        public boolean hasChildren(Object element) {
            if (!(element instanceof GLCallNode)) {
                return false;
            }

            return ((GLCallNode) element).hasChildren();
        }
    }

    private class GLFrameLabelProvider extends ColumnLabelProvider {
        @Override
        public void update(ViewerCell cell) {
            Object element = cell.getElement();
            if (!(element instanceof GLCallNode)) {
                return;
            }

            GLCall c = ((GLCallNode) element).getCall();

            if (c.getFunction() == Function.glDrawArrays
                    || c.getFunction() == Function.glDrawElements) {
                cell.setForeground(mGldrawTextColor);
            }

            if (c.hasErrors()) {
                cell.setForeground(mGlCallErrorColor);
            }

            cell.setText(getColumnText(c, cell.getColumnIndex()));
        }

        private String getColumnText(GLCall c, int columnIndex) {
            switch (columnIndex) {
            case 0:
                if (c.getFunction() == Function.glPushGroupMarkerEXT) {
                    Object marker = c.getProperty(GLCall.PROPERTY_MARKERNAME);
                    if (marker instanceof String) {
                        return ((String) marker);
                    }
                }
                try {
                    return c.toString();
                } catch (Exception e) {
                    // in case of any formatting errors, just return the function name.
                    return c.getFunction().toString();
                }
            case 1:
                return formatDuration(c.getWallDuration());
            case 2:
                return formatDuration(c.getThreadDuration());
            default:
                return Integer.toString(c.getContextId());
            }
        }

        private String formatDuration(int time) {
            // Max duration is in the 10s of milliseconds, so xx,xxx,xxx ns
            // So we require a format specifier that is 10 characters wide
            return String.format("%,10d", time);            //$NON-NLS-1$
        }
    }

    private static class GLCallFilter extends ViewerFilter {
        private final List<Pattern> mPatterns = new ArrayList<Pattern>();

        public void setFilters(String filter) {
            mPatterns.clear();

            // split the user input into multiple regexes
            // we assume that the regexes are OR'ed together i.e., all text that matches
            // any one of the regexes will be displayed
            for (String regex : filter.split(" ")) {
                mPatterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            }
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element) {
            if (!(element instanceof GLCallNode)) {
                return true;
            }

            String text = getTextUnderNode((GLCallNode) element);

            if (mPatterns.size() == 0) {
                // match if there are no regex filters
                return true;
            }

            for (Pattern p : mPatterns) {
                Matcher matcher = p.matcher(text);
                if (matcher.find()) {
                    // match if atleast one of the regexes matches this text
                    return true;
                }
            }

            return false;
        }

        /** Obtain a string representation of all functions under a given tree node. */
        private String getTextUnderNode(GLCallNode element) {
            String func = element.getCall().getFunction().toString();
            if (!element.hasChildren()) {
                return func;
            }

            StringBuilder sb = new StringBuilder(100);
            sb.append(func);

            for (GLCallNode child : element.getChildren()) {
                sb.append(getTextUnderNode(child));
            }

            return sb.toString();
        }
    }

    @Override
    public void addSelectionChangedListener(ISelectionChangedListener listener) {
        if (mFrameTreeViewer != null) {
            mFrameTreeViewer.addSelectionChangedListener(listener);
        }
    }

    @Override
    public ISelection getSelection() {
        if (mFrameTreeViewer != null) {
            return mFrameTreeViewer.getSelection();
        } else {
            return null;
        }
    }

    @Override
    public void removeSelectionChangedListener(ISelectionChangedListener listener) {
        if (mFrameTreeViewer != null) {
            mFrameTreeViewer.removeSelectionChangedListener(listener);
        }
    }

    @Override
    public void setSelection(ISelection selection) {
        if (mFrameTreeViewer != null) {
            mFrameTreeViewer.setSelection(selection);
        }
    }

    public GLTrace getTrace() {
        return mTrace;
    }

    public StateViewPage getStateViewPage() {
        return new StateViewPage(mTrace);
    }

    public FrameSummaryViewPage getFrameSummaryViewPage() {
        if (mFrameSummaryViewPage == null) {
            mFrameSummaryViewPage = new FrameSummaryViewPage(mTrace);
        }

        return mFrameSummaryViewPage;
    }

    public DetailsPage getDetailsPage() {
        return new DetailsPage(mTrace);
    }

    private void copySelectionToClipboard() {
        if (mFrameTreeViewer == null || mFrameTreeViewer.getTree().isDisposed()) {
            return;
        }

        StringBuilder sb = new StringBuilder();

        for (TreeItem it: mFrameTreeViewer.getTree().getSelection()) {
            Object data = it.getData();
            if (data instanceof GLCallNode) {
                sb.append(((GLCallNode) data).getCall());
                sb.append(NEWLINE);
            }
        }

        if (sb.length() > 0) {
            Clipboard cb = new Clipboard(Display.getDefault());
            cb.setContents(
                    new Object[] { sb.toString() },
                    new Transfer[] { TextTransfer.getInstance() });
            cb.dispose();
        }
    }

    private void selectAll() {
        if (mFrameTreeViewer == null || mFrameTreeViewer.getTree().isDisposed()) {
            return;
        }

        mFrameTreeViewer.getTree().selectAll();
    }

    private class TraceViewerFindTarget extends AbstractBufferFindTarget {
        @Override
        public int getItemCount() {
            return mFrameTreeViewer.getTree().getItemCount();
        }

        @Override
        public String getItem(int index) {
            Object data = mFrameTreeViewer.getTree().getItem(index).getData();
            if (data instanceof GLCallNode) {
                return ((GLCallNode) data).getCall().toString();
            }
            return null;
        }

        @Override
        public void selectAndReveal(int index) {
            Tree t = mFrameTreeViewer.getTree();
            t.deselectAll();
            t.select(t.getItem(index));
            t.showSelection();
        }

        @Override
        public int getStartingIndex() {
            return 0;
        }
    };

    private FindDialog mFindDialog;
    private TraceViewerFindTarget mFindTarget = new TraceViewerFindTarget();

    private void showFindDialog() {
        if (mFindDialog != null) {
            // the dialog is already displayed
            return;
        }

        mFindDialog = new FindDialog(Display.getDefault().getActiveShell(),
                mFindTarget,
                FindDialog.FIND_NEXT_ID);
        mFindDialog.open(); // blocks until find dialog is closed
        mFindDialog = null;
    }
}
