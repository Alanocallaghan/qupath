/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.gui.viewer.tools.handlers;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.tools.PathTools;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClassTools;
import qupath.lib.objects.classes.Reclassifier;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.PolylineROI;
import qupath.lib.roi.RoiEditor;
import qupath.lib.roi.interfaces.ROI;

/**
 * Abstract PathTool for drawing ROIs.
 * 
 * @author Pete Bankhead
 *
 */
abstract class AbstractPathROIToolEventHandler extends AbstractPathToolEventHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(AbstractPathROIToolEventHandler.class);

	/**
	 * Create a new ROI with the given starting coordinates.
	 * 
	 * @param e
	 * @param x
	 * @param y
	 * @param plane
	 * @return
	 */
	protected abstract ROI createNewROI(MouseEvent e, double x, double y, ImagePlane plane);
	
	/**
	 * Create a new annotation and set it in the current viewer.
	 * @param e 
	 * @param x
	 * @param y
	 * @return
	 */
	protected PathObject createNewAnnotation(MouseEvent e, double x, double y) {
		
		var viewer = getViewer();
		var currentObject = viewer.getSelectedObject();
		var editor = viewer.getROIEditor();
		if (currentObject != null && currentObject.getParent() == null && currentObject.getROI() == editor.getROI() && (editor.isTranslating() || editor.hasActiveHandle())) {
			logger.warn("Creating a new annotation before a previous one was complete - {} will be discarded!", currentObject);
		}
		
		logger.trace("Creating new annotation at ({}, {}", x, y);
		PathObjectHierarchy hierarchy = viewer.getHierarchy();
		if (hierarchy == null) {
			logger.warn("Cannot create new annotation - no hierarchy available!");
			return null;
		}
		ROI roi = createNewROI(e, x, y, viewer.getImagePlane());
		if (roi == null)
			return null;
		
		PathObject pathObject = PathObjects.createAnnotationObject(roi, PathPrefs.autoSetAnnotationClassProperty().get());
		var selectionModel = hierarchy.getSelectionModel();
		if (PathPrefs.selectionModeStatus().get() && !selectionModel.noSelection())
			viewer.setSelectedObject(pathObject, true);		
		else
			viewer.setSelectedObject(pathObject);
		return pathObject;
	}
	
	
	@Override
	public void mousePressed(MouseEvent e) {
		super.mousePressed(e);
		if (!e.isPrimaryButtonDown() || e.isConsumed()) {
            return;
        }
		
		var viewer = getViewer();
		PathObjectHierarchy hierarchy = viewer.getHierarchy();
		if (hierarchy == null)
			return;
		
		PathObject currentObject = viewer.getSelectedObject();
		ROI currentROI = currentObject == null ? null : currentObject.getROI();
		RoiEditor editor = viewer.getROIEditor();
		
		boolean adjustingPolygon = (currentROI instanceof PolygonROI || currentROI instanceof PolylineROI) && editor.getROI() == currentROI && (editor.isTranslating() || editor.hasActiveHandle());

		// If we're adjusting a polygon/polyline with an appropriate tool, return at leave it up to the tool to handle the custom things
		if (adjustingPolygon) {
			if (viewer.getActiveTool() == PathTools.POLYGON || viewer.getActiveTool() == PathTools.POLYLINE)
				return;
			else {
				viewer.getHierarchy().getSelectionModel().clearSelection();
				viewer.getHierarchy().fireHierarchyChangedEvent(currentObject);
			}
		}

		// Find out the coordinates in the image domain
		Point2D p2 = mouseLocationToImage(e, false, requestPixelSnapping());
		double xx = p2.getX();
		double yy = p2.getY();
		if (xx < 0 || yy < 0 || xx >= viewer.getServerWidth() || yy >= viewer.getServerHeight())
			return;
						
		// If we are double-clicking & we don't have a polygon, see if we can access a ROI
		if (!PathPrefs.selectionModeStatus().get() && e.getClickCount() > 1) {
			// Reset parent... for now
			resetConstrainingObjects();
			ToolUtils.tryToSelect(viewer, xx, yy, e.getClickCount()-2, false);
			e.consume();
			return;
		}

		// Set the current parent object based on the first click
		updatingConstrainingObjects(viewer, xx, yy, Collections.emptyList());
		
		// Create a new annotation
		PathObject pathObject = createNewAnnotation(e, xx, yy);
		if (pathObject == null)
			return;
		
		// Start editing the ROI immediately
		editor.setROI(pathObject.getROI());
		editor.grabHandle(xx, yy, viewer.getMaxROIHandleSize() * 1.5, e.isShiftDown());
	}
	
	
	
	@Override
	public void mouseMoved(MouseEvent e) {
		super.mouseMoved(e);
		ensureCursorType(Cursor.CROSSHAIR);
	}

	
	/**
	 * Return true if this tool would prefer to switch back to 'Move' after a ROI has been drawn.
	 * @return
	 */
	protected boolean preferReturnToMove() {
		return PathPrefs.returnToMoveModeProperty().get();
	}
	
	
	/**
	 * When drawing an object is complete, add it to the hierarchy - or whatever else is required.
	 * 
	 * @param e
	 * @param pathObject
	 */
	void commitObjectToHierarchy(MouseEvent e, PathObject pathObject) {
		if (pathObject == null)
			return;
		
		var viewer = getViewer();
		PathObjectHierarchy hierarchy = viewer.getHierarchy();
		
		var currentROI = pathObject.getROI();
		
		// If we are in selection mode, try to get objects to select
		if (PathPrefs.selectionModeStatus().get()) {
			var pathClass = e.isAltDown() ? null : PathPrefs.autoSetAnnotationClassProperty().get();
			Collection<PathObject> toSelect;
			if (currentROI.isArea()) {
				toSelect = hierarchy.getAllObjectsForROI(currentROI);
			} else if (currentROI.isPoint()) {
//				toSelect = hierarchy.getObjectsAtPoint(currentROI.getCentroidX(), currentROI.getCentroidY());
				toSelect = new HashSet<>();
				for (var p : currentROI.getAllPoints()) {
					toSelect.addAll(
							PathObjectTools.getObjectsForLocation(hierarchy, p.getX(), p.getY(), currentROI.getZ(), currentROI.getT(), 0.0)
					);
				}
			} else {
				var geom = currentROI.getGeometry();
				toSelect = hierarchy.getAllDetectionsForRegion(ImageRegion.createInstance(currentROI))
						.parallelStream()
						.filter(p -> geom.intersects(p.getROI().getGeometry()))
						.toList();
			}
			if (!toSelect.isEmpty() && pathClass != null) {
				boolean retainIntensityClass = !(PathClassTools.isPositiveOrGradedIntensityClass(pathClass) || PathClassTools.isNegativeClass(pathClass));
				var reclassified = toSelect.stream()
						.filter(p -> p.getPathClass() != pathClass)
						.map(p -> new Reclassifier(p, pathClass, retainIntensityClass))
						.filter(Reclassifier::apply)
						.map(Reclassifier::getPathObject)
						.toList();
				if (!reclassified.isEmpty()) {
					hierarchy.fireObjectClassificationsChangedEvent(this, reclassified);
				}
			}
			if (pathObject.getParent() != null)
				hierarchy.removeObject(pathObject, true);
			//				else
			//					viewer.getHierarchy().fireHierarchyChangedEvent(this);
			if (toSelect.isEmpty())
				viewer.setSelectedObject(null);
			else if (e.isAltDown()) {
				hierarchy.getSelectionModel().deselectObject(pathObject);
				hierarchy.getSelectionModel().deselectObjects(toSelect);
			} else if (e.isShiftDown()) {
				hierarchy.getSelectionModel().deselectObject(pathObject);
				hierarchy.getSelectionModel().selectObjects(toSelect);
			} else
				hierarchy.getSelectionModel().setSelectedObjects(toSelect, null);
		} else {
			if (!requestParentClipping(e)) {
				if (currentROI.isEmpty()) {
					// We may be removing an object that was already removed from the hierarchy (during editing) 
					// - if so, we need to notify listeners somehow
					if (pathObject != null) {
						if (pathObject.getParent() == null)
							hierarchy.fireHierarchyChangedEvent(this);
						else
							hierarchy.removeObject(pathObject, true);
					}
					pathObject = null;
				} else
					hierarchy.addObject(pathObject); // Ensure object is within the hierarchy
			} else {
				ROI roiNew = refineROIByParent(pathObject.getROI());
				if (roiNew.isEmpty()) {
					hierarchy.removeObject(pathObject, true);
					pathObject = null;
				} else {
					((PathAnnotationObject)pathObject).setROI(roiNew);
					hierarchy.addObjectBelowParent(getCurrentParent(), pathObject, true);
				}
			}
			if (pathObject != null)
				viewer.setSelectedObject(pathObject);
			else
				viewer.getHierarchy().getSelectionModel().clearSelection();
		}
				
		var editor = viewer.getROIEditor();
		editor.ensureHandlesUpdated();
		editor.resetActiveHandle();
		
		if (preferReturnToMove()) {
			var qupath = QuPathGUI.getInstance();
			if (qupath != null)
				qupath.getToolManager().setSelectedTool(PathTools.MOVE);
		}
	}

	
}
