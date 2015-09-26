/*
 * Copyright 2014-2015 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kotcrab.vis.editor.ui.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop.Payload;
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop.Source;
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop.Target;
import com.google.common.eventbus.Subscribe;
import com.kotcrab.vis.editor.App;
import com.kotcrab.vis.editor.Editor;
import com.kotcrab.vis.editor.Log;
import com.kotcrab.vis.editor.event.*;
import com.kotcrab.vis.editor.module.ContentTable;
import com.kotcrab.vis.editor.module.ModuleContainer;
import com.kotcrab.vis.editor.module.VisContainers;
import com.kotcrab.vis.editor.module.editor.ExtensionStorageModule;
import com.kotcrab.vis.editor.module.editor.MenuBarModule;
import com.kotcrab.vis.editor.module.editor.StatusBarModule;
import com.kotcrab.vis.editor.module.project.*;
import com.kotcrab.vis.editor.module.scene.*;
import com.kotcrab.vis.editor.module.scene.entitymanipulator.AlignmentToolsDialog;
import com.kotcrab.vis.editor.module.scene.entitymanipulator.EntityManipulatorModule;
import com.kotcrab.vis.editor.plugin.EditorEntitySupport;
import com.kotcrab.vis.editor.proxy.EntityProxy;
import com.kotcrab.vis.editor.scene.EditorScene;
import com.kotcrab.vis.editor.ui.scene.entityproperties.EntityProperties;
import com.kotcrab.vis.editor.ui.tab.CloseTabWhenMovingResources;
import com.kotcrab.vis.editor.ui.tabbedpane.DragAndDropTarget;
import com.kotcrab.vis.editor.ui.tabbedpane.MainContentTab;
import com.kotcrab.vis.editor.ui.tabbedpane.TabViewMode;
import com.kotcrab.vis.editor.util.gdx.FocusUtils;
import com.kotcrab.vis.editor.util.gdx.VisValue;
import com.kotcrab.vis.editor.util.vis.CreatePointPayload;
import com.kotcrab.vis.runtime.util.EntityEngine;
import com.kotcrab.vis.ui.util.dialog.DialogUtils;
import com.kotcrab.vis.ui.widget.VisTable;

/**
 * Main tab for scene editor, allows to edit scene, holds and provides all features related to it. Uses it's own
 * {@link ModuleContainer} ({@link SceneModuleContainer})
 * @author Kotcrab
 */
public class SceneTab extends MainContentTab implements DragAndDropTarget, CloseTabWhenMovingResources {
	private EditorScene scene;

	private ExtensionStorageModule pluginContainer;
	private MenuBarModule menuBarModule;
	private StatusBarModule statusBarModule;
	private SceneTabsModule sceneTabs;
	private FileAccessModule fileAccess;
	private SceneIOModule sceneIOModule;

	private SceneModuleContainer sceneMC;

	private EntityManipulatorModule entityManipulator;
	private UndoModule undoModule;
	private CameraModule cameraModule;

	private Stage stage;

	private EntityEngine engine;
	private EntityCounterManager entityCounter;
	private EntityProxyCache entityProxyCache;

	private ContentTable content;

	private boolean savedAtLeastOnce;
	private boolean lastSaveFailed;

	private Target dropTarget;
	private final AlignmentToolsDialog alignmentTools;

	public SceneTab (EditorScene scene, ProjectModuleContainer projectMC) {
		super(true);
		this.scene = scene;
		stage = Editor.instance.getStage();

		sceneMC = new SceneModuleContainer(projectMC, this, scene, stage.getBatch());
		VisContainers.createSceneModules(sceneMC, projectMC.findInHierarchy(ExtensionStorageModule.class));

		for (EditorEntitySupport support : projectMC.get(SupportModule.class).getSupports()) {
			support.registerSystems(sceneMC, sceneMC.getEntityEngineConfiguration());
		}

		sceneMC.init();
		sceneMC.injectModules(this);
		engine = sceneMC.getEntityEngine();

		entityCounter = engine.getManager(EntityCounterManager.class);
		entityProxyCache = engine.getManager(EntityProxyCache.class);

		VisTable leftColumn = new VisTable(false);
		VisTable rightColumn = new VisTable(false);

		leftColumn.top();
		rightColumn.top();

		content = new ContentTable(sceneMC);

		GroupBreadcrumb breadcrumb = entityManipulator.getGroupBreadcrumb();
		EntityProperties entityProperties = entityManipulator.getEntityProperties();
		LayersDialog layersDialog = entityManipulator.getLayersDialog();
		alignmentTools = entityManipulator.getAlignmentToolsDialog();

		content.add(breadcrumb).height(new VisValue(context -> breadcrumb.getPrefHeight())).expandX().fillX().colspan(3).row();
		content.add(leftColumn).width(190).fillY().expandY();
		content.add().fill().expand();
		content.add(rightColumn).width(280).fillY().expandY();

		//we need some better window management, really
		leftColumn.top();
		leftColumn.add(alignmentTools).height(new VisValue(context -> alignmentTools.getPrefHeight())).expandX().fillX().row();
		leftColumn.add(entityManipulator.getSceneOutline())
				.height(300).padTop(new VisValue(context -> alignmentTools.isVisible() ? 8 : 0))
				.top().fillX().expandX().row();
		leftColumn.add(entityManipulator.getToolPropertiesContainer()).bottom().expand().fillX();

		rightColumn.top();
		rightColumn.add(entityProperties)
				.height(new VisValue(context -> Math.min(entityProperties.getPrefHeight(), rightColumn.getHeight() - layersDialog.getHeight() - 8)))
				.maxHeight(new VisValue(context -> rightColumn.getHeight() - layersDialog.getHeight() - 8))
				.expandX().fillX().top().row();
		rightColumn.add(layersDialog).bottom().expand().fillX();

		dropTarget = new Target(content) {
			@Override
			public void drop (Source source, Payload payload, float x, float y, int pointer) {
				entityManipulator.processDropPayload(payload.getObject());
			}

			@Override
			public boolean drag (Source source, Payload payload, float x, float y, int pointer) {
				return true;
			}
		};

		App.eventBus.register(this);

		//reload all assets on next frame (after EntityEngine registers all entities)
		Gdx.app.postRunnable(() -> handleResourceReloaded(new ResourceReloadedEvent(Integer.MAX_VALUE)));
	}

	@Override
	public void render (Batch batch) {
		statusBarModule.setInfoLabelText(getInfoLabelText());

		Color oldColor = batch.getColor().cpy();
		batch.setColor(1, 1, 1, 1);
		batch.begin();

		sceneMC.render(batch);

		batch.end();
		batch.setColor(oldColor);
	}

	@Override
	public String getTabTitle () {
		return scene.getFile().name();
	}

	@Override
	public Table getContentTable () {
		return content;
	}

	@Override
	public Target getDropTarget () {
		return dropTarget;
	}

	@Override
	public float getPixelsPerUnit () {
		return scene.pixelsPerUnit;
	}

	@Override
	public float getCameraZoom () {
		return cameraModule.getZoom();
	}

	@Override
	public EntityEngine getEntityEngine () {
		return engine;
	}

	@Override
	public TabViewMode getViewMode () {
		return TabViewMode.SPLIT;
	}

	@Override
	public void onShow () {
		super.onShow();
		sceneMC.onShow();
		menuBarModule.setSceneTab(this);
		focusSelf();
		App.eventBus.post(new SceneTabShowEvent(sceneMC));
	}

	@Override
	public void onHide () {
		super.onHide();
		sceneMC.onHide();
		menuBarModule.setSceneTab(null);
		statusBarModule.setInfoLabelText("");
		App.eventBus.post(new SceneTabHideEvent());
	}

	public EditorScene getScene () {
		return scene;
	}

	@Subscribe
	public void handleResourceReloaded (ResourceReloadedEvent event) {
		if ((event.resourceType & ResourceReloadedEvent.RESOURCE_TEXTURES) != 0) {
			sceneMC.getEntityEngine().getManager(TextureReloaderManager.class).reloadTextures();
		}

		if ((event.resourceType & ResourceReloadedEvent.RESOURCE_PARTICLES) != 0) {
			sceneMC.getEntityEngine().getManager(ParticleReloaderManager.class).reloadParticles();
		}

		if ((event.resourceType & ResourceReloadedEvent.RESOURCE_SHADERS) != 0) {
			sceneMC.getEntityEngine().getManager(ShaderReloaderManager.class).reloadShaders();
		}

		if ((event.resourceType & ResourceReloadedEvent.RESOURCE_BMP_FONTS) != 0) {
			sceneMC.getEntityEngine().getManager(FontReloaderManager.class).reloadFonts(true, false);
		}

		if ((event.resourceType & ResourceReloadedEvent.RESOURCE_TTF_FONTS) != 0) {
			sceneMC.getEntityEngine().getManager(FontReloaderManager.class).reloadFonts(false, true);
		}

		if ((event.resourceType & ResourceReloadedEvent.RESOURCE_SPRITER_DATA) != 0) {
			sceneMC.getEntityEngine().getManager(SpriterReloaderManager.class).reloadSpriterData();
		}
	}

	@Subscribe
	public void handleSceneMenuBarEvent (SceneMenuBarEvent event) {
		if (isActiveTab() == false) return;

		switch (event.type) {
			case SHOW_ALIGNMENT_TOOLS:
				alignmentTools.setVisible(true);
				break;
			case SHOW_SCENE_SETTINGS:
				stage.addActor(new SceneSettingsDialog(this).fadeIn());
				break;
			case SHOW_PHYSICS_SETTINGS:
				stage.addActor(new PhysicsSettingsDialog(sceneMC).fadeIn());
				break;
			case RESET_CAMERA:
				cameraModule.reset();
				break;
			case RESET_ZOOM:
				cameraModule.resetZoom();
				break;
			case UNDO:
				undoModule.undo();
				break;
			case REDO:
				undoModule.redo();
				break;
			case GROUP:
				entityManipulator.groupSelection();
				break;
			case UNGROUP:
				entityManipulator.ungroupSelection();
				break;
			case ADD_NEW_POINT:
				entityManipulator.processDropPayload(new CreatePointPayload(true));
				break;
		}
	}

	@Subscribe
	public void handleToolbarEvent (ToolbarEvent event) {
		if (isActiveTab()) {
			if (event.type == ToolbarEventType.FILE_SAVE)
				save();
		}
	}

	@Subscribe
	public void handleUndoEvent (UndoEvent event) {
		if (event.origin == sceneMC && undoModule.getUndoSize() == 0 && savedAtLeastOnce == false) {
			setDirty(false);
		}
	}

	@Override
	public boolean save () {
		super.save();
		scene.setSchemes(sceneMC.getEntityEngine().getManager(EntityProxyCache.class).getSchemes());
		try {
			FileHandle sceneFile = sceneIOModule.getFileHandleForScene(scene);
			FileHandle backupTarget = sceneIOModule.getSceneBackupFolder().child(scene.path);

			if (lastSaveFailed == false) {
				sceneFile.copyTo(backupTarget.sibling(sceneFile.name() + ".bak"));
			}

			if (savedAtLeastOnce == false) {
				sceneFile.copyTo(backupTarget.sibling(sceneFile.name() + ".firstSaveBak"));
			}

			if (sceneIOModule.save(scene)) {
				setDirty(false);
				sceneMC.save();
				savedAtLeastOnce = true;
				lastSaveFailed = false;
				return true;
			} else {
				lastSaveFailed = true;
				DialogUtils.showErrorDialog(stage, "Unknown error encountered while saving resource");
			}

		} catch (Exception e) {
			lastSaveFailed = true;
			Log.exception(e);
			DialogUtils.showErrorDialog(stage, "Unknown error encountered while saving resource", e);
		}

		return false;
	}

	@Override
	public void dispose () {
		sceneMC.dispose();
		App.eventBus.unregister(this);
	}

	public String getNextUndoActionName () {
		return undoModule.getNextUndoActionName();
	}

	public String getInfoLabelText () {
		return "Entities: " + entityCounter.getEntityCount() + " FPS: " + Gdx.graphics.getFramesPerSecond() + " Scene: " + scene.width + " x " + scene.height;
	}

	public void centerAround (int entityId) {
		centerAround(entityProxyCache.get(entityId));
	}

	public void centerAround (EntityProxy entity) {
		entityManipulator.findEntityBaseGroupAndSelect(entity);
		cameraModule.setPosition(entity.getX() + entity.getWidth() / 2, entity.getY() + entity.getHeight() / 2);
	}

	public void focusSelf () {
		FocusUtils.focus(stage, content);
	}

	@Override
	public void reopenSelfAfterAssetsUpdated () {
		save();
		sceneTabs.open(scene);
	}

	public SceneModuleContainer getSceneMC () {
		return sceneMC;
	}
}
