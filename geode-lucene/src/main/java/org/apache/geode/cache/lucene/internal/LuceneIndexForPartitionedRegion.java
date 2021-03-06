/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.cache.lucene.internal;

import org.apache.geode.CancelException;
import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.FixedPartitionResolver;
import org.apache.geode.cache.PartitionAttributes;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.PartitionResolver;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.asyncqueue.internal.AsyncEventQueueImpl;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.ResultCollector;
import org.apache.geode.cache.lucene.internal.directory.DumpDirectoryFiles;
import org.apache.geode.cache.lucene.internal.filesystem.ChunkKey;
import org.apache.geode.cache.lucene.internal.filesystem.File;
import org.apache.geode.cache.lucene.internal.filesystem.FileSystemStats;
import org.apache.geode.cache.lucene.internal.partition.BucketTargetingFixedResolver;
import org.apache.geode.cache.lucene.internal.partition.BucketTargetingResolver;
import org.apache.geode.cache.lucene.internal.repository.RepositoryManager;
import org.apache.geode.cache.lucene.internal.repository.serializer.HeterogeneousLuceneSerializer;
import org.apache.geode.cache.partition.PartitionListener;
import org.apache.geode.distributed.internal.DM;
import org.apache.geode.distributed.internal.ReplyException;
import org.apache.geode.distributed.internal.ReplyProcessor21;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.PartitionedRegion;

import java.util.Set;

/* wrapper of IndexWriter */
public class LuceneIndexForPartitionedRegion extends LuceneIndexImpl {
  protected Region<String, File> fileRegion;
  protected Region<ChunkKey, byte[]> chunkRegion;
  protected final FileSystemStats fileSystemStats;

  public static final String FILES_REGION_SUFFIX = ".files";
  public static final String CHUNKS_REGION_SUFFIX = ".chunks";

  public LuceneIndexForPartitionedRegion(String indexName, String regionPath, Cache cache) {
    super(indexName, regionPath, cache);

    final String statsName = indexName + "-" + regionPath;
    this.fileSystemStats = new FileSystemStats(cache.getDistributedSystem(), statsName);
  }

  protected RepositoryManager createRepositoryManager() {
    RegionShortcut regionShortCut;
    final boolean withPersistence = withPersistence();
    RegionAttributes regionAttributes = dataRegion.getAttributes();
    final boolean withStorage = regionAttributes.getPartitionAttributes().getLocalMaxMemory() > 0;

    // TODO: 1) dataRegion should be withStorage
    // 2) Persistence to Persistence
    // 3) Replicate to Replicate, Partition To Partition
    // 4) Offheap to Offheap
    if (!withStorage) {
      regionShortCut = RegionShortcut.PARTITION_PROXY;
    } else if (withPersistence) {
      // TODO: add PartitionedRegionAttributes instead
      regionShortCut = RegionShortcut.PARTITION_PERSISTENT;
    } else {
      regionShortCut = RegionShortcut.PARTITION;
    }

    // create PR fileRegion, but not to create its buckets for now
    final String fileRegionName = createFileRegionName();
    PartitionAttributes partitionAttributes = dataRegion.getPartitionAttributes();
    if (!fileRegionExists(fileRegionName)) {
      fileRegion =
          createFileRegion(regionShortCut, fileRegionName, partitionAttributes, regionAttributes);
    }

    // create PR chunkRegion, but not to create its buckets for now
    final String chunkRegionName = createChunkRegionName();

    // we will create RegionDirectories on the fly when data comes in
    HeterogeneousLuceneSerializer mapper = new HeterogeneousLuceneSerializer(getFieldNames());
    PartitionedRepositoryManager partitionedRepositoryManager =
        new PartitionedRepositoryManager(this, mapper);
    DM dm = ((GemFireCacheImpl) getCache()).getDistributedSystem().getDistributionManager();
    LuceneBucketListener lucenePrimaryBucketListener =
        new LuceneBucketListener(partitionedRepositoryManager, dm);
    if (!chunkRegionExists(chunkRegionName)) {
      chunkRegion = createChunkRegion(regionShortCut, fileRegionName, partitionAttributes,
          chunkRegionName, regionAttributes, lucenePrimaryBucketListener);
    }
    fileSystemStats.setFileSupplier(() -> (int) getFileRegion().getLocalSize());
    fileSystemStats.setChunkSupplier(() -> (int) getChunkRegion().getLocalSize());
    fileSystemStats.setBytesSupplier(() -> getChunkRegion().getPrStats().getDataStoreBytesInUse());

    return partitionedRepositoryManager;
  }

  public PartitionedRegion getFileRegion() {
    return (PartitionedRegion) fileRegion;
  }

  public PartitionedRegion getChunkRegion() {
    return (PartitionedRegion) chunkRegion;
  }

  public FileSystemStats getFileSystemStats() {
    return fileSystemStats;
  }

  boolean fileRegionExists(String fileRegionName) {
    return cache.<String, File>getRegion(fileRegionName) != null;
  }

  Region createFileRegion(final RegionShortcut regionShortCut, final String fileRegionName,
      final PartitionAttributes partitionAttributes, final RegionAttributes regionAttributes) {
    return createRegion(fileRegionName, regionShortCut, this.regionPath, partitionAttributes,
        regionAttributes, null);
  }

  public String createFileRegionName() {
    return LuceneServiceImpl.getUniqueIndexRegionName(indexName, regionPath, FILES_REGION_SUFFIX);
  }

  boolean chunkRegionExists(String chunkRegionName) {
    return cache.<ChunkKey, byte[]>getRegion(chunkRegionName) != null;
  }

  Region<ChunkKey, byte[]> createChunkRegion(final RegionShortcut regionShortCut,
      final String fileRegionName, final PartitionAttributes partitionAttributes,
      final String chunkRegionName, final RegionAttributes regionAttributes,
      final PartitionListener lucenePrimaryBucketListener) {
    return createRegion(chunkRegionName, regionShortCut, fileRegionName, partitionAttributes,
        regionAttributes, lucenePrimaryBucketListener);
  }

  public String createChunkRegionName() {
    return LuceneServiceImpl.getUniqueIndexRegionName(indexName, regionPath, CHUNKS_REGION_SUFFIX);
  }

  private PartitionAttributesFactory configureLuceneRegionAttributesFactory(
      PartitionAttributesFactory attributesFactory,
      PartitionAttributes<?, ?> dataRegionAttributes) {
    attributesFactory.setTotalNumBuckets(dataRegionAttributes.getTotalNumBuckets());
    attributesFactory.setRedundantCopies(dataRegionAttributes.getRedundantCopies());
    attributesFactory.setPartitionResolver(getPartitionResolver(dataRegionAttributes));
    return attributesFactory;
  }

  private PartitionResolver getPartitionResolver(PartitionAttributes dataRegionAttributes) {
    if (dataRegionAttributes.getPartitionResolver() instanceof FixedPartitionResolver) {
      return new BucketTargetingFixedResolver();
    } else {
      return new BucketTargetingResolver();
    }
  }

  protected <K, V> Region<K, V> createRegion(final String regionName,
      final RegionShortcut regionShortCut, final String colocatedWithRegionName,
      final PartitionAttributes partitionAttributes, final RegionAttributes regionAttributes,
      PartitionListener lucenePrimaryBucketListener) {
    PartitionAttributesFactory partitionAttributesFactory =
        new PartitionAttributesFactory<String, File>();
    if (lucenePrimaryBucketListener != null) {
      partitionAttributesFactory.addPartitionListener(lucenePrimaryBucketListener);
    }
    partitionAttributesFactory.setColocatedWith(colocatedWithRegionName);
    configureLuceneRegionAttributesFactory(partitionAttributesFactory, partitionAttributes);

    // Create AttributesFactory based on input RegionShortcut
    RegionAttributes baseAttributes = this.cache.getRegionAttributes(regionShortCut.toString());
    AttributesFactory factory = new AttributesFactory(baseAttributes);
    factory.setPartitionAttributes(partitionAttributesFactory.create());
    factory.setDiskStoreName(regionAttributes.getDiskStoreName());
    RegionAttributes<K, V> attributes = factory.create();

    return createRegion(regionName, attributes);
  }

  public void close() {
    // TODO Auto-generated method stub

  }

  @Override
  public void dumpFiles(final String directory) {
    ResultCollector results = FunctionService.onRegion(getDataRegion())
        .withArgs(new String[] {directory, indexName}).execute(DumpDirectoryFiles.ID);
    results.getResult();
  }

  @Override
  public void destroy(boolean initiator) {
    if (logger.isDebugEnabled()) {
      logger.debug("Destroying index regionPath=" + regionPath + "; indexName=" + indexName
          + "; initiator=" + initiator);
    }

    // Invoke super destroy to remove the extension
    super.destroy(initiator);

    // Destroy the AsyncEventQueue
    PartitionedRegion pr = (PartitionedRegion) getDataRegion();
    destroyAsyncEventQueue(pr);

    // Destroy the chunk region (colocated with the file region)
    // localDestroyRegion can't be used because locally destroying regions is not supported on
    // colocated regions
    if (!chunkRegion.isDestroyed()) {
      chunkRegion.destroyRegion();
      if (logger.isDebugEnabled()) {
        logger.debug("Destroyed chunkRegion=" + chunkRegion.getName());
      }
    }

    // Destroy the file region (colocated with the application region)
    // localDestroyRegion can't be used because locally destroying regions is not supported on
    // colocated regions
    if (!fileRegion.isDestroyed()) {
      fileRegion.destroyRegion();
      if (logger.isDebugEnabled()) {
        logger.debug("Destroyed fileRegion=" + fileRegion.getName());
      }
    }

    // Destroy index on remote members if necessary
    if (initiator) {
      destroyOnRemoteMembers(pr);
    }

    if (logger.isDebugEnabled()) {
      logger.debug("Destroyed index regionPath=" + regionPath + "; indexName=" + indexName
          + "; initiator=" + initiator);
    }
  }

  private void destroyAsyncEventQueue(PartitionedRegion pr) {
    String aeqId = LuceneServiceImpl.getUniqueIndexName(indexName, regionPath);

    // Get the AsyncEventQueue
    AsyncEventQueueImpl aeq = (AsyncEventQueueImpl) cache.getAsyncEventQueue(aeqId);

    // Stop the AsyncEventQueue (this stops the AsyncEventQueue's underlying GatewaySender)
    aeq.stop();

    // Remove the id from the dataRegion's AsyncEventQueue ids
    // Note: The region may already have been destroyed by a remote member
    if (!pr.isDestroyed()) {
      pr.getAttributesMutator().removeAsyncEventQueueId(aeqId);
    }

    // Destroy the aeq (this also removes it from the GemFireCacheImpl)
    aeq.destroy();
    if (logger.isDebugEnabled()) {
      logger.debug("Destroyed aeqId=" + aeqId);
    }
  }

  private void destroyOnRemoteMembers(PartitionedRegion pr) {
    DM dm = pr.getDistributionManager();
    Set<InternalDistributedMember> recipients = pr.getRegionAdvisor().adviseDataStore();
    if (!recipients.isEmpty()) {
      if (logger.isDebugEnabled()) {
        logger.debug("LuceneIndexForPartitionedRegion: About to send destroy message recipients="
            + recipients);
      }
      ReplyProcessor21 processor = new ReplyProcessor21(dm, recipients);
      DestroyLuceneIndexMessage message = new DestroyLuceneIndexMessage(recipients,
          processor.getProcessorId(), regionPath, indexName);
      dm.putOutgoing(message);
      if (logger.isDebugEnabled()) {
        logger.debug("LuceneIndexForPartitionedRegion: Sent message recipients=" + recipients);
      }
      try {
        processor.waitForReplies();
      } catch (ReplyException e) {
        if (!(e.getCause() instanceof CancelException)) {
          throw e;
        }
      } catch (InterruptedException e) {
        dm.getCancelCriterion().checkCancelInProgress(e);
        Thread.currentThread().interrupt();
      }
    }
  }
}
