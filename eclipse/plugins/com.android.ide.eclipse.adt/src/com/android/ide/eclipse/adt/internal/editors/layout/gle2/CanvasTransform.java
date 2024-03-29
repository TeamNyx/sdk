/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.ide.eclipse.adt.internal.editors.layout.gle2;

import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.ScrollBar;

/**
 * Helper class to convert between control pixel coordinates and canvas coordinates.
 * Takes care of the zooming and offset of the canvas.
 */
public class CanvasTransform {
    /**
     * Default margin around the rendered image, reduced
     * when the contents do not fit.
     */
    public static final int DEFAULT_MARGIN = 25;

    /**
     * The canvas which controls the zooming.
     */
    private final LayoutCanvas mCanvas;

    /** Canvas image size (original, before zoom), in pixels. */
    private int mImgSize;

    /** Client size, in pixels. */
    private int mClientSize;

    /** Left-top offset in client pixel coordinates. */
    private int mTranslate;

    /** Current margin */
    private int mMargin = DEFAULT_MARGIN;

    /** Scaling factor, > 0. */
    private double mScale;

    /** Scrollbar widget. */
    private ScrollBar mScrollbar;

    public CanvasTransform(LayoutCanvas layoutCanvas, ScrollBar scrollbar) {
        mCanvas = layoutCanvas;
        mScrollbar = scrollbar;
        mScale = 1.0;
        mTranslate = 0;

        mScrollbar.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                // User requested scrolling. Changes translation and redraw canvas.
                mTranslate = mScrollbar.getSelection();
                CanvasTransform.this.mCanvas.redraw();
            }
        });
        mScrollbar.setIncrement(20);
    }

    /**
     * Sets the new scaling factor. Recomputes scrollbars.
     * @param scale Scaling factor, > 0.
     */
    public void setScale(double scale) {
        if (mScale != scale) {
            mScale = scale;
            resizeScrollbar();
        }
    }

    /**
     * Returns current scaling factor.
     *
     * @return The current scaling factor
     */
    public double getScale() {
        return mScale;
    }

    /**
     * Returns Canvas image size (original, before zoom), in pixels.
     *
     * @return Canvas image size (original, before zoom), in pixels
     */
    public int getImgSize() {
        return mImgSize;
    }

    /**
     * Returns the scaled image size in pixels.
     *
     * @return The scaled image size in pixels.
     */
    public int getScaledImgSize() {
        return (int) (mImgSize * mScale);
    }

    /** Changes the size of the canvas image and the client size. Recomputes scrollbars. */
    public void setSize(int imgSize, int clientSize) {
        mImgSize = imgSize;
        setClientSize(clientSize);
    }

    /** Changes the size of the client size. Recomputes scrollbars. */
    public void setClientSize(int clientSize) {
        mClientSize = clientSize;
        mScrollbar.setPageIncrement(clientSize);
        resizeScrollbar();
    }

    private void resizeScrollbar() {
        // scaled image size
        int sx = (int) (mImgSize * mScale);

        // Adjust margin such that for zoomed out views
        // we don't waste space (unless the viewport is
        // large enough to accommodate it)
        int delta = mClientSize - sx;
        if (delta < 0) {
            mMargin = 0;
        } else if (delta < 2 * DEFAULT_MARGIN) {
            mMargin = delta / 2;
        } else {
            mMargin = DEFAULT_MARGIN;
        }

        // actual client area is always reduced by the margins
        int cx = mClientSize - 2 * mMargin;

        if (sx < cx) {
            mTranslate = 0;
            mScrollbar.setEnabled(false);
        } else {
            mScrollbar.setEnabled(true);

            int selection = mScrollbar.getSelection();
            int thumb = cx;
            int maximum = sx;

            if (selection + thumb > maximum) {
                selection = maximum - thumb;
                if (selection < 0) {
                    selection = 0;
                }
            }

            mScrollbar.setValues(selection, mScrollbar.getMinimum(), maximum, thumb, mScrollbar
                    .getIncrement(), mScrollbar.getPageIncrement());

            mTranslate = selection;
        }
    }

    public int getMargin() {
        return mMargin;
    }

    public int translate(int canvasX) {
        return mMargin - mTranslate + (int) (mScale * canvasX);
    }

    public int scale(int canwasW) {
        return (int) (mScale * canwasW);
    }

    public int inverseTranslate(int screenX) {
        return (int) ((screenX - mMargin + mTranslate) / mScale);
    }

    public int inverseScale(int canwasW) {
        return (int) (canwasW / mScale);
    }
}
