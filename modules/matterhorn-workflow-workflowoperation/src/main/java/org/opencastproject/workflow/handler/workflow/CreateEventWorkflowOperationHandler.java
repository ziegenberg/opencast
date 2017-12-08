/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.workflow.handler.workflow;

import static org.opencastproject.workflow.handler.distribution.InternalPublicationChannel.CHANNEL_ID;

import org.opencastproject.assetmanager.api.AssetManager;
import org.opencastproject.assetmanager.api.Property;
import org.opencastproject.assetmanager.api.PropertyId;
import org.opencastproject.assetmanager.api.Value;
import org.opencastproject.assetmanager.api.query.AQueryBuilder;
import org.opencastproject.assetmanager.api.query.AResult;
import org.opencastproject.assetmanager.api.query.PropertyField;
import org.opencastproject.assetmanager.api.query.PropertySchema;
import org.opencastproject.distribution.api.DistributionService;
import org.opencastproject.ingest.api.IngestService;
import org.opencastproject.job.api.Job;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageBuilderFactory;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.MediaPackageElements;
import org.opencastproject.mediapackage.MediaPackageException;
import org.opencastproject.mediapackage.Publication;
import org.opencastproject.mediapackage.PublicationImpl;
import org.opencastproject.mediapackage.selector.SimpleElementSelector;
import org.opencastproject.metadata.dublincore.DublinCore;
import org.opencastproject.metadata.dublincore.DublinCoreCatalog;
import org.opencastproject.metadata.dublincore.DublinCoreUtil;
import org.opencastproject.util.JobUtil;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;
import org.opencastproject.workspace.api.Workspace;

import com.entwinemedia.fn.data.Opt;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;


/**
 * This WOH creates new events from an input event.
 */
public class CreateEventWorkflowOperationHandler extends AbstractWorkflowOperationHandler {

  private static final Logger logger = LoggerFactory.getLogger(CreateEventWorkflowOperationHandler.class);
  private static final String PLUS = "+";
  private static final String MINUS = "-";

  /** The configuration options for this handler */
  private static final SortedMap<String, String> CONFIG_OPTIONS;

  /** Name of the configuration option that provides the source flavors we are looking for */
  public static final String SOURCE_FLAVORS_PROPERTY = "source-flavors";

  /** Name of the configuration option that provides the source tags we are looking for */
  public static final String SOURCE_TAGS_PROPERTY = "source-tags";

  /** Name of the configuration option that provides the target tags we should apply */
  public static final String TARGET_TAGS_PROPERTY = "target-tags";

  /** Name of the configuration option that provides the number of events to create */
  public static final String NUMBER_PROPERTY = "number-of-events";

  /** The namespaces of the asset manager properties to copy. */
  public static final String PROPERTY_NAMESPACES_PROPERTY = "property-namespaces";

  /** The prefix to use for the number which is appended to the original title of the event. */
  public static final String COPY_NUMBER_PREFIX_PROPERTY = "copy-number-prefix";

  /** The name of the asset manager property to store the current count of copies. */
  public static final String COPY_COUNT_PROPERTY_NAME = "copyCount";

  /** AssetManager to use for creating new media packages. */
  private AssetManager assetManager;

  /** The workspace to use for retrieving and storing files. */
  protected Workspace workspace;

  /** The distribution service */
  protected DistributionService distributionService;

  /** The ingest service */
  private IngestService ingestService;

  static {
    CONFIG_OPTIONS = new TreeMap<String, String>();
    CONFIG_OPTIONS.put(SOURCE_FLAVORS_PROPERTY,
            "Copy any mediapackage elements with one of these (comma separated) flavors.");
    CONFIG_OPTIONS.put(SOURCE_TAGS_PROPERTY,
            "Copy any mediapackage elements with one of these (comma separated) tags.");
    CONFIG_OPTIONS
            .put(TARGET_TAGS_PROPERTY,
                    "Apply these (comma separated) tags to any mediapackage elements. If a target-tag starts with a '-', "
                            + "tag will removed from preexisting tags, if starts with a '+', tag will added to preexisting tags. "
                            + "If there is no prefix, all preexisting tags are removed and replaced by the target-tags");
    CONFIG_OPTIONS.put(NUMBER_PROPERTY,
            "How many events to create. Must be a positive integer number.");
    CONFIG_OPTIONS.put(PROPERTY_NAMESPACES_PROPERTY,
            "Copy all asset manager properties of these (comma separated) namespaces.");
    CONFIG_OPTIONS.put(COPY_NUMBER_PREFIX_PROPERTY,
            "Use this prefix for the number appended to the title(s) of the new event(s).");
  }

  /**
   * Callback for the OSGi declarative services configuration.
   *
   * @param assetManager
   *          the asset manager
   */
  public void setAssetManager(AssetManager assetManager) {
    this.assetManager = assetManager;
  }

  /**
   * Callback for the OSGi declarative services configuration.
   *
   * @param ingestService
   *          the service to set
   */
  public void setIngestService(IngestService ingestService) {
    this.ingestService = ingestService;
  }

  /**
   * Callback for the OSGi declarative services configuration.
   *
   * @param workspace
   *          the workspace
   */
  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  /**
   * Callback for the OSGi declarative services configuration.
   *
   * @param distributionService
   *          the distributionService to set
   */
  public void setDistributionService(DistributionService distributionService) {
    this.distributionService = distributionService;
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler#getConfigurationOptions()
   */
  @Override
  public SortedMap<String, String> getConfigurationOptions() {
    return CONFIG_OPTIONS;
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler#start(WorkflowInstance,
   *      JobContext)
   */
  @Override
  public WorkflowOperationResult start(final WorkflowInstance workflowInstance, final JobContext context)
          throws WorkflowOperationException {

    final MediaPackage mediaPackage = workflowInstance.getMediaPackage();
    final WorkflowOperationInstance currentOperation = workflowInstance.getCurrentOperation();

    final String configuredSourceFlavors = StringUtils.trimToEmpty(currentOperation.getConfiguration(SOURCE_FLAVORS_PROPERTY));
    final String configuredSourceTags = StringUtils.trimToEmpty(currentOperation.getConfiguration(SOURCE_TAGS_PROPERTY));
    final String configuredTargetTags = StringUtils.trimToEmpty(currentOperation.getConfiguration(TARGET_TAGS_PROPERTY));
    final int numberOfEvents = Integer.parseInt(currentOperation.getConfiguration(NUMBER_PROPERTY));
    final String configuredPropertyNamespaces = StringUtils.trimToEmpty(currentOperation.getConfiguration(PROPERTY_NAMESPACES_PROPERTY));

    logger.info("Creating {} new media packages from media package with id {}.", numberOfEvents, mediaPackage.getIdentifier());

    final String[] sourceTags = StringUtils.split(configuredSourceTags, ",");
    final String[] targetTags = StringUtils.split(configuredTargetTags, ",");
    final String[] sourceFlavors = StringUtils.split(configuredSourceFlavors, ",");
    final String[] propertyNamespaces = StringUtils.split(configuredPropertyNamespaces, ",");
    final String copyNumberPrefix = StringUtils.trimToEmpty(currentOperation.getConfiguration(COPY_NUMBER_PREFIX_PROPERTY));

    final SimpleElementSelector elementSelector = new SimpleElementSelector();
    for (String flavor : sourceFlavors) {
      elementSelector.addFlavor(MediaPackageElementFlavor.parseFlavor(flavor));
    }

    final List<String> removeTags = new ArrayList<String>();
    final List<String> addTags = new ArrayList<String>();
    final List<String> overrideTags = new ArrayList<String>();

    for (String tag : targetTags) {
      if (tag.startsWith(MINUS)) {
        removeTags.add(tag);
      } else if (tag.startsWith(PLUS)) {
        addTags.add(tag);
      } else {
        overrideTags.add(tag);
      }
    }

    for (String tag : sourceTags) {
      elementSelector.addTag(tag);
    }

    // Filter elements to copy based on input tags and input flavors
    final Collection<MediaPackageElement> elements = elementSelector.select(mediaPackage, true);
    final Collection<Publication> internalPublications = new HashSet<>();

    for (MediaPackageElement e: mediaPackage.getElements()) {
      if (e instanceof Publication && CHANNEL_ID.equals(((Publication) e).getChannel())) {
        internalPublications.add((Publication) e);
      }
      if (MediaPackageElements.EPISODE.equals(e.getFlavor())) {
        // Remove episode DC since we will add a new one (with changed title)
        elements.remove(e);
      }
    }

    final MediaPackageElement[] episodeDcs = mediaPackage.getElementsByFlavor(MediaPackageElements.EPISODE);
    Opt<MediaPackageElement> originalDublinCore = Opt.none();
    if (episodeDcs.length == 1) {
      originalDublinCore = Opt.some(episodeDcs[0]);
    } else {
      logger.warn("Media package {} has {} episode dublin cores while it is expected to have exactly 1.",
              mediaPackage.getIdentifier(),
              episodeDcs.length);
    }

    for (int i = 0; i < numberOfEvents; i++) {

      final long copyNumber = getCopyCount(mediaPackage) + 1;

      // Clone the media package (without its elements)
      MediaPackage newMp = copyMediaPackage(mediaPackage, copyNumber, copyNumberPrefix);

      // Clone episode dublin core
      if (originalDublinCore.isSome()) {
        // Create and add new episode dublin core with changed title
        newMp = copyDublinCore(mediaPackage, originalDublinCore.get(), newMp, removeTags, addTags, overrideTags);
      }

      // Clone regular elements
      for (final MediaPackageElement e : elements) {
        final MediaPackageElement element = (MediaPackageElement) e.clone();
        updateTags(element, removeTags, addTags, overrideTags);
        newMp.add(element);
      }

      // Clone internal publications
      for (final Publication originalPub: internalPublications) {
        copyPublication(originalPub, mediaPackage, newMp, removeTags, addTags, overrideTags);
      }

      assetManager.takeSnapshot(AssetManager.DEFAULT_OWNER, newMp);

      // Clone properties of media package
      for (String namespace : propertyNamespaces) {
        copyProperties(namespace, mediaPackage, newMp);
      }

      setCopyCount(mediaPackage, copyNumber);
    }
    return createResult(mediaPackage, Action.CONTINUE);
  }

  private void updateTags(MediaPackageElement element, List<String> removeTags, List<String> addTags, List<String> overrideTags) {
    element.setIdentifier(null);

    if (overrideTags.size() > 0) {
      element.clearTags();
      for (String tag : overrideTags) {
        element.addTag(tag);
      }
    } else {
      for (String tag : removeTags) {
        element.removeTag(tag.substring(MINUS.length()));
      }
      for (String tag : addTags) {
        element.addTag(tag.substring(PLUS.length()));
      }
    }
  }

  private MediaPackage copyMediaPackage(MediaPackage source, long copyNumber, String copyNumberPrefix) throws WorkflowOperationException {
    // We are not using MediaPackage.clone() here, since it does "too much" for us (e.g. copies all the attachments)
    MediaPackage destination;
    try {
      destination = MediaPackageBuilderFactory.newInstance().newMediaPackageBuilder().createNew();
    } catch (MediaPackageException e) {
      logger.error("Failed to create media package " + e.getLocalizedMessage());
      throw new WorkflowOperationException(e);
    }
    logger.info("Created mediapackage {}", destination);
    destination.setDate(source.getDate());
    destination.setSeries(source.getSeries());
    destination.setSeriesTitle(source.getSeriesTitle());
    destination.setDuration(source.getDuration());
    destination.setLanguage(source.getLanguage());
    destination.setLicense(source.getLicense());
    destination.setTitle(source.getTitle() + " (" + copyNumberPrefix + " " + copyNumber + ")");
    return destination;
  }

  private void copyPublication(
          Publication sourcePublication,
          MediaPackage source,
          MediaPackage destination,
          List<String> removeTags,
          List<String> addTags,
          List<String> overrideTags) throws WorkflowOperationException {
    final String newPublicationId = UUID.randomUUID().toString();
    final Publication newPublication = PublicationImpl.publication(newPublicationId, CHANNEL_ID, null, null);

    // re-distribute elements of publication to internal publication channel
    final Collection<MediaPackageElement> sourcePubElements = new HashSet<>();
    sourcePubElements.addAll(Arrays.asList(sourcePublication.getAttachments()));
    sourcePubElements.addAll(Arrays.asList(sourcePublication.getCatalogs()));
    sourcePubElements.addAll(Arrays.asList(sourcePublication.getTracks()));
    for (final MediaPackageElement e: sourcePubElements) {
      try {
        // We first have to copy the media package element into the workspace
        final MediaPackageElement element = (MediaPackageElement) e.clone();
        final File sourceFile = workspace.get(element.getURI());
        final URI tmpUri = workspace.put(
                destination.getIdentifier().toString(), element.getIdentifier().toString(),
                FilenameUtils.getName(element.getURI().toString()),
                new FileInputStream(sourceFile)
        );
        element.setIdentifier(null);
        element.setURI(tmpUri);

        // Now we can distribute it to the new media package
        destination.add(element); // Element has to be added before it can be distributed
        final Job j = distributionService.distribute(CHANNEL_ID, destination, element.getIdentifier());
        final MediaPackageElement distributedElement = JobUtil.payloadAsMediaPackageElement(serviceRegistry).apply(j);
        destination.remove(element);

        updateTags(distributedElement, removeTags, addTags, overrideTags);

        PublicationImpl.addElementToPublication(newPublication, distributedElement);
      } catch (Exception exception) {
        throw new WorkflowOperationException(exception);
      }
    }

    // Using an altered copy of the source publication's URI is a bit hacky, but it works without knowing the URI pattern...
    String publicationUri = sourcePublication.getURI().toString();
    publicationUri = publicationUri.replace(source.getIdentifier().toString(), destination.getIdentifier().toString());
    publicationUri = publicationUri.replace(sourcePublication.getIdentifier().toString(), newPublicationId);
    newPublication.setURI(URI.create(publicationUri));
    destination.add(newPublication);
  }

  private MediaPackage copyDublinCore(
          MediaPackage source,
          MediaPackageElement sourceDublinCore,
          MediaPackage destination,
          List<String> removeTags,
          List<String> addTags,
          List<String> overrideTags) throws WorkflowOperationException {
    final DublinCoreCatalog destinationDublinCore = DublinCoreUtil.loadEpisodeDublinCore(workspace, source).get();
    destinationDublinCore.setIdentifier(null);
    destinationDublinCore.setURI(sourceDublinCore.getURI());
    destinationDublinCore.set(DublinCore.PROPERTY_TITLE, destination.getTitle());
    destinationDublinCore.setFlavor(sourceDublinCore.getFlavor());
    for (String tag: sourceDublinCore.getTags()) {
      destinationDublinCore.addTag(tag);
    }
    updateTags(destinationDublinCore, removeTags, addTags, overrideTags);
    try (InputStream inputStream = IOUtils.toInputStream(destinationDublinCore.toXmlString(), "UTF-8")) {
      destination = ingestService.addCatalog(inputStream, "dublincore.xml", MediaPackageElements.EPISODE, destination);
    } catch (Exception e) {
      throw new WorkflowOperationException(e);
    }
    return destination;
  }

  private void copyProperties(String namespace, MediaPackage source, MediaPackage destination) {
    final AQueryBuilder q = assetManager.createQuery();
    final AResult properties = q.select(q.propertiesOf(namespace))
            .where(q.mediaPackageId(source.getIdentifier().toString())).run();
    if (properties.getRecords().head().isNone()) {
      logger.error("AssetManager could not find source media package {}. This should never happen.", source.getIdentifier(), namespace);
      return;
    }
    for (final Property p : properties.getRecords().head().get().getProperties()) {
      final PropertyId newPropId = PropertyId.mk(destination.getIdentifier().toString(), namespace, p.getId().getName());
      assetManager.setProperty(Property.mk(newPropId, p.getValue()));
    }
  }

  private void setCopyCount(MediaPackage mediaPackage, long copyNumber) {
    final AQueryBuilder q = assetManager.createQuery();
    final Props p = new Props(q);
    assetManager.setProperty(p.copyCount().mk(mediaPackage.getIdentifier().toString(), copyNumber));
  }

  private long getCopyCount(MediaPackage mediaPackage) {
    final AQueryBuilder q = assetManager.createQuery();
    final Props p = new Props(q);
    final AResult property = q.select(p.copyCount().target())
            .where(q.mediaPackageId(mediaPackage.getIdentifier().toString())).run();
    if (property.getRecords().head().isNone() || property.getRecords().head().get().getProperties().head().isNone()) {
      return 0;
    }
    return property.getRecords().head().get().getProperties().head().get().getValue().get(Value.LONG);
  }

  private static class Props extends PropertySchema {
    Props(AQueryBuilder q) {
      super(q, CreateEventWorkflowOperationHandler.class.getName());
    }
    public PropertyField<Long> copyCount() {
      return longProp(COPY_COUNT_PROPERTY_NAME);
    }
  }
}
