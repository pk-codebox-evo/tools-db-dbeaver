/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2016 Serge Rieder (serge@jkiss.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.ui.controls.resultset.panel;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDValue;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.ui.ActionUtils;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.*;
import org.jkiss.dbeaver.ui.data.IValueController;
import org.jkiss.dbeaver.ui.data.IValueEditor;
import org.jkiss.dbeaver.ui.data.IValueManager;
import org.jkiss.dbeaver.ui.data.editors.BaseValueEditor;
import org.jkiss.dbeaver.ui.data.editors.ReferenceValueEditor;
import org.jkiss.utils.CommonUtils;

/**
 * RSV value view panel
 */
public class ViewValuePanel implements IResultSetPanel {

    private static final Log log = Log.getLog(ViewValuePanel.class);

    public static final String PANEL_ID = "value-view";

    private IResultSetPresentation presentation;
    private Composite viewPlaceholder;

    private ResultSetValueController previewController;
    private IValueEditor valueEditor;
    private ReferenceValueEditor referenceValueEditor;

    private volatile boolean valueSaving;
    private IValueManager valueManager;

    public ViewValuePanel() {
    }

    @Override
    public String getPanelTitle() {
        return "Value";
    }

    @Override
    public DBPImage getPanelImage() {
        return DBIcon.TYPE_OBJECT;
    }

    @Override
    public String getPanelDescription() {
        return "Value view/edit";
    }

    @Override
    public Control createContents(IResultSetPresentation presentation, Composite parent) {
        this.presentation = presentation;

        viewPlaceholder = new Composite(parent, SWT.NONE);
        viewPlaceholder.setLayout(new FillLayout());
        viewPlaceholder.addPaintListener(new PaintListener() {
            @Override
            public void paintControl(PaintEvent e) {
                if (viewPlaceholder.getChildren().length == 0) {
                    String hidePanelCmd = ActionUtils.findCommandDescription(
                        ResultSetCommandHandler.CMD_TOGGLE_PANELS,
                        ViewValuePanel.this.presentation.getController().getSite(),
                        true);

                    UIUtils.drawMessageOverControl(viewPlaceholder, e, "Select a cell to view/edit value", 0);
                    UIUtils.drawMessageOverControl(viewPlaceholder, e, "Press " + hidePanelCmd + " to hide this panel", 20);
                }
            }
        });

/*
        addTraverseListener(new TraverseListener() {
            @Override
            public void keyTraversed(TraverseEvent e) {
                if (e.detail == SWT.TRAVERSE_ESCAPE) {
                    hidePanel();
                    e.doit = false;
                }
            }
        });
*/

        if (presentation instanceof ISelectionProvider) {
            ((ISelectionProvider) presentation).addSelectionChangedListener(new ISelectionChangedListener() {
                @Override
                public void selectionChanged(SelectionChangedEvent event) {
                    refreshValue();
                }
            });
        }

        return viewPlaceholder;
    }

    @Override
    public void activatePanel() {
        refreshValue();
    }

    @Override
    public void deactivatePanel() {

    }

    @Override
    public void refresh() {
        refreshValue();
    }

    @Override
    public void contributeActions(ToolBarManager manager) {
        fillToolBar(manager);
    }

    private void refreshValue() {
        DBDAttributeBinding attr = presentation.getCurrentAttribute();
        ResultSetRow row = presentation.getController().getCurrentRow();
        if (attr == null || row == null) {
            clearValue();
            return;
        }
        ResultSetValueController newController;
        boolean updateActions = false;
        if (previewController == null || previewController.getBinding() != attr) {
            newController = new ResultSetValueController(
                presentation.getController(),
                attr,
                row,
                IValueController.EditType.PANEL,
                viewPlaceholder)
            {
                @Override
                public void updateValue(@Nullable Object value) {
                    super.updateValue(value);
                    presentation.updateValueView();
                }
            };
            updateActions = true;
        } else {
            newController = previewController;
            previewController.setCurRow(row);
        }
        viewValue(newController);
        if (updateActions) {
            presentation.getController().updatePanelActions();
        }
    }

    private void viewValue(final ResultSetValueController valueController)
    {
        if (valueSaving) {
            return;
        }
        if (previewController == null || valueController.getValueType() != previewController.getValueType()) {
            cleanupPanel();
            // Create a new one
            valueManager = valueController.getValueManager();
            try {
                valueEditor = valueManager.createEditor(valueController);
            } catch (DBException e) {
                UIUtils.showErrorDialog(viewPlaceholder.getShell(), "Value preview", "Can't create value viewer", e);
                return;
            }
            if (valueEditor != null) {
                try {
                    valueEditor.createControl();
                } catch (Exception e) {
                    log.error(e);
                }
                Control control = valueEditor.getControl();
                if (control != null) {
                    presentation.getController().lockActionsByFocus(control);
                }

                referenceValueEditor = new ReferenceValueEditor(valueController, valueEditor);
                if (referenceValueEditor.isReferenceValue()) {
                    GridLayout gl = new GridLayout(1, false);
                    viewPlaceholder.setLayout(gl);
                    valueEditor.getControl().setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
                    referenceValueEditor.createEditorSelector(viewPlaceholder);
                } else {
                    viewPlaceholder.setLayout(new FillLayout());
                }

            } else {
                final Composite placeholder = UIUtils.createPlaceholder(viewPlaceholder, 1);
                placeholder.setBackground(placeholder.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
                placeholder.addPaintListener(new PaintListener() {
                    @Override
                    public void paintControl(PaintEvent e)
                    {
                        Rectangle bounds = placeholder.getBounds();
                        String message = "No editor for [" + valueController.getValueType().getTypeName() + "]";
                        Point ext = e.gc.textExtent(message);
                        e.gc.drawText(message, (bounds.width - ext.x) / 2, bounds.height / 3 + 20);
                    }
                });
                referenceValueEditor = null;
            }
            previewController = valueController;

            viewPlaceholder.layout();
        }
        if (valueEditor instanceof BaseValueEditor) {
            ((BaseValueEditor) valueEditor).setAutoSaveEnabled(false);
        }
        if (valueEditor != null) {
            try {
                Object newValue = previewController.getValue();
                if (newValue instanceof DBDValue) {
                    // Do not check for difference
                    valueEditor.primeEditorValue(newValue);
                } else {
                    Object oldValue = valueEditor.extractEditorValue();
                    if (!CommonUtils.equalObjects(oldValue, newValue)) {
                        valueEditor.primeEditorValue(newValue);
                    }
                }
            } catch (DBException e) {
                log.error(e);
            }
            valueEditor.setDirty(false);
        }
        if (valueEditor instanceof BaseValueEditor) {
            ((BaseValueEditor) valueEditor).setAutoSaveEnabled(true);
        }
    }

    public void saveValue()
    {
        if (valueEditor == null) {
            return;
        }
        try {
            valueSaving = true;
            Object newValue = valueEditor.extractEditorValue();
            previewController.updateValue(newValue);
            presentation.updateValueView();
        } catch (Exception e) {
            UIUtils.showErrorDialog(null, "Value save", "Can't save edited value", e);
        } finally {
            valueSaving = false;
        }
    }

    public void clearValue()
    {
        cleanupPanel();

        presentation.getController().updateEditControls();
        viewPlaceholder.layout();
    }

    private void cleanupPanel()
    {
        // Cleanup previous viewer
        for (Control child : viewPlaceholder.getChildren()) {
            child.dispose();
        }
        previewController = null;
    }

    private void fillToolBar(final IContributionManager contributionManager)
    {
        contributionManager.add(new Separator());
        //contributionManager.add(new Separator());
        if (valueManager != null) {
            try {
                valueManager.contributeActions(contributionManager, previewController, valueEditor);
            } catch (Exception e) {
                log.error("Can't contribute value manager actions", e);
            }
        }

        contributionManager.add(
            ActionUtils.makeCommandContribution(presentation.getController().getSite(), ValueViewCommandHandler.CMD_SAVE_VALUE));

        contributionManager.add(
            new Action("Auto-save value", Action.AS_CHECK_BOX) {
                {
                    setImageDescriptor(DBeaverIcons.getImageDescriptor(UIIcon.LINK_TO_EDITOR));
                }
                @Override
                public boolean isChecked() {
                    return DBeaverCore.getGlobalPreferenceStore().getBoolean(DBeaverPreferences.RS_EDIT_AUTO_UPDATE_VALUE);
                }

                @Override
                public void run() {
                    boolean newValue = !isChecked();
                    DBeaverCore.getGlobalPreferenceStore().setValue(DBeaverPreferences.RS_EDIT_AUTO_UPDATE_VALUE, newValue);
                    presentation.getController().updatePanelActions();
                }
            });
    }

}
