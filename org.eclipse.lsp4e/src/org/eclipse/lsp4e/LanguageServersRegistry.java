/*******************************************************************************
 * Copyright (c) 2016 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e;

import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.lsp4e.server.StreamConnectionProvider;

/**
 * This registry aims at providing a good language server connection (as {@link StreamConnectionProvider}
 * for a given input.
 * At the moment, registry content are hardcoded but we'll very soon need a way
 * to contribute to it via plugin.xml (for plugin developers) and from Preferences
 * (for end-users to directly register a new server).
 *
 */
public class LanguageServersRegistry {

	private static final String CONTENT_TYPE_TO_LSP_LAUNCH_PREF_KEY = "contentTypeToLSPLauch"; //$NON-NLS-1$

	private static final String EXTENSION_POINT_ID = LanguageServerPlugin.PLUGIN_ID + ".languageServer"; //$NON-NLS-1$

	private static final String LS_ELEMENT = "server"; //$NON-NLS-1$
	private static final String MAPPING_ELEMENT = "contentTypeMapping"; //$NON-NLS-1$

	private static final String ID_ATTRIBUTE = "id"; //$NON-NLS-1$
	private static final String CONTENT_TYPE_ATTRIBUTE = "contentType"; //$NON-NLS-1$
	private static final String CLASS_ATTRIBUTE = "class"; //$NON-NLS-1$
	private static final String LABEL_ATTRIBUTE = "label"; //$NON-NLS-1$

	public static abstract class LanguageServerDefinition {
		private final @NonNull String id;
		private final @NonNull String label;

		public LanguageServerDefinition(@NonNull String id, @NonNull String label) {
			this.id = id;
			this.label = label;
		}

		public String getId() {
			return id;
		}

		public String getLabel() {
			return label;
		}

		public abstract StreamConnectionProvider createConnectionProvider();
	}

	static class ExtensionLanguageServerDefinition extends LanguageServerDefinition {
		private IConfigurationElement extension;

		public ExtensionLanguageServerDefinition(IConfigurationElement element) {
			super(element.getAttribute(ID_ATTRIBUTE), element.getAttribute(LABEL_ATTRIBUTE));
			this.extension = element;
		}

		@Override
		public StreamConnectionProvider createConnectionProvider() {
			try {
				return (StreamConnectionProvider) extension.createExecutableExtension(CLASS_ATTRIBUTE);
			} catch (CoreException e) {
				return null;
			}
		}
	}

	static class LaunchConfigurationLanguageServerDefinition extends LanguageServerDefinition {
		final ILaunchConfiguration launchConfiguration;
		final Set<String> launchModes;

		public LaunchConfigurationLanguageServerDefinition(ILaunchConfiguration launchConfiguration,
				Set<String> launchModes) {
			super(launchConfiguration.getName(), launchConfiguration.getName());
			this.launchConfiguration = launchConfiguration;
			this.launchModes = launchModes;
		}

		@Override
		public StreamConnectionProvider createConnectionProvider() {
			return new LaunchConfigurationStreamProvider(this.launchConfiguration, launchModes);
		}
	}

	private static LanguageServersRegistry INSTANCE = null;
	public static LanguageServersRegistry getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new LanguageServersRegistry();
		}
		return INSTANCE;
	}

	private List<ContentTypeToLanguageServerDefinition> connections = new ArrayList<>();
	private IPreferenceStore preferenceStore;

	private LanguageServersRegistry() {
		this.preferenceStore = LanguageServerPlugin.getDefault().getPreferenceStore();
		initialize();
	}

	private void initialize() {
		String prefs = preferenceStore.getString(CONTENT_TYPE_TO_LSP_LAUNCH_PREF_KEY);
		if (prefs != null && !prefs.isEmpty()) {
			String[] entries = prefs.split(","); //$NON-NLS-1$
			for (String entry : entries) {
				ContentTypeToLSPLaunchConfigEntry mapping = ContentTypeToLSPLaunchConfigEntry.readFromPreference(entry);
				if (mapping != null) {
					connections.add(mapping);
				}
			}
		}

		Map<String, LanguageServerDefinition> servers = new HashMap<>();
		List<Entry<IContentType, String>> contentTypes = new ArrayList<>();
		for (IConfigurationElement extension : Platform.getExtensionRegistry().getConfigurationElementsFor(EXTENSION_POINT_ID)) {
			String id = extension.getAttribute(ID_ATTRIBUTE);
			if (id != null && !id.isEmpty()) {
				if (extension.getName().equals(LS_ELEMENT)) {
					servers.put(id, new ExtensionLanguageServerDefinition(extension));
				} else if (extension.getName().equals(MAPPING_ELEMENT)) {
					IContentType contentType = Platform.getContentTypeManager().getContentType(extension.getAttribute(CONTENT_TYPE_ATTRIBUTE));
					if (contentType != null) {
						contentTypes.add(new SimpleEntry<>(contentType, id));
					}
				}
			}
		}
		for (Entry<IContentType, String> entry : contentTypes) {
			IContentType contentType = entry.getKey();
			LanguageServerDefinition lsDefinition = servers.get(entry.getValue());
			if (lsDefinition != null) {
				registerAssociation(contentType, lsDefinition);
			} else {
				LanguageServerPlugin.logWarning("server '" + entry.getValue() + "' not available", null); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
	}

	private void persistContentTypeToLaunchConfigurationMapping() {
		StringBuilder builder = new StringBuilder();
		for (ContentTypeToLSPLaunchConfigEntry entry : getContentTypeToLSPLaunches()) {
			entry.appendPreferenceTo(builder);
			builder.append(',');
		}
		if (builder.length() > 0) {
			builder.deleteCharAt(builder.length() - 1);
		}
		this.preferenceStore.setValue(CONTENT_TYPE_TO_LSP_LAUNCH_PREF_KEY, builder.toString());
		if (this.preferenceStore instanceof IPersistentPreferenceStore) {
			try {
				((IPersistentPreferenceStore) this.preferenceStore).save();
			} catch (IOException e) {
				LanguageServerPlugin.logError(e);
			}
		}
	}

	public List<LanguageServerDefinition> findProviderFor(final IContentType contentType) {
		return connections.stream()
			.filter(entry -> entry.getKey().equals(contentType))
			.map(Entry::getValue)
			.collect(Collectors.toList());
	}

	public void registerAssociation(@NonNull IContentType contentType, @NonNull ILaunchConfiguration launchConfig, @NonNull Set<String> launchMode) {
		ContentTypeToLSPLaunchConfigEntry mapping = new ContentTypeToLSPLaunchConfigEntry(contentType, launchConfig, launchMode);
		connections.add(mapping);
		persistContentTypeToLaunchConfigurationMapping();
	}

	public void registerAssociation(@NonNull IContentType contentType, @NonNull LanguageServerDefinition serverDefinition) {
		connections.add(new ContentTypeToLanguageServerDefinition(contentType, serverDefinition));
	}

	public void setAssociations(List<ContentTypeToLSPLaunchConfigEntry> wc) {
		this.connections.removeIf(ContentTypeToLSPLaunchConfigEntry.class::isInstance);
		this.connections.addAll(wc);
		persistContentTypeToLaunchConfigurationMapping();
	}

	public List<ContentTypeToLSPLaunchConfigEntry> getContentTypeToLSPLaunches() {
		return this.connections.stream().filter(ContentTypeToLSPLaunchConfigEntry.class::isInstance).map(ContentTypeToLSPLaunchConfigEntry.class::cast).collect(Collectors.toList());
	}

	public List<ContentTypeToLanguageServerDefinition> getContentTypeToLSPExtensions() {
		return this.connections.stream().filter(mapping -> mapping.getValue() instanceof ExtensionLanguageServerDefinition).collect(Collectors.toList());
	}

	public @Nullable LanguageServerDefinition getDefinition(@NonNull String languageServerId) {
		for (ContentTypeToLanguageServerDefinition mapping : this.connections) {
			if (mapping.getValue().id.equals(languageServerId)) {
				return mapping.getValue();
			}
		}
		return null;
	}

}
