package org.ethereum.sync;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.ethereum.core.*;
import org.ethereum.net.server.Channel;
import org.ethereum.validator.BlockHeaderValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;

import static java.lang.Math.max;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/**
 * Created by Anton Nashatyrev on 27.10.2016.
 */
public abstract class BlockDownloader {

    private final static Logger logger = LoggerFactory.getLogger("sync");

    private int blockQueueLimit = 2000;
    private int headerQueueLimit = 10000;

    // Max number of Blocks / Headers in one request
    private static int MAX_IN_REQUEST = 192;
    private static int REQUESTS = 32;

    private BlockHeaderValidator headerValidator;

    private SyncPool pool;

    private SyncQueueIfc syncQueue;

    private boolean headersDownload = true;
    private boolean blockBodiesDownload = true;

    private CountDownLatch receivedHeadersLatch = new CountDownLatch(0);
    private CountDownLatch receivedBlocksLatch = new CountDownLatch(0);

    private Thread getHeadersThread;
    private Thread getBodiesThread;

    protected boolean headersDownloadComplete;
    private boolean downloadComplete;

    private CountDownLatch stopLatch = new CountDownLatch(1);

    public BlockDownloader(BlockHeaderValidator headerValidator) {
        this.headerValidator = headerValidator;
    }

    protected abstract void pushBlocks(List<BlockWrapper> blockWrappers);
    protected abstract void pushHeaders(List<BlockHeaderWrapper> headers);
    protected abstract int getBlockQueueFreeSize();

    protected void finishDownload() {}

    public boolean isDownloadComplete() {
        return downloadComplete;
    }

    public void setBlockBodiesDownload(boolean blockBodiesDownload) {
        this.blockBodiesDownload = blockBodiesDownload;
    }

    public void setHeadersDownload(boolean headersDownload) {
        this.headersDownload = headersDownload;
    }

    public void init(SyncQueueIfc syncQueue, final SyncPool pool) {
        this.syncQueue = syncQueue;
        this.pool = pool;

        logger.info("Initializing BlockDownloader.");

        if (headersDownload) {
            getHeadersThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    headerRetrieveLoop();
                }
            }, "SyncThreadHeaders");
            getHeadersThread.start();
        }

        if (blockBodiesDownload) {
            getBodiesThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    blockRetrieveLoop();
                }
            }, "SyncThreadBlocks");
            getBodiesThread.start();
        }
    }

    public void stop() {
        if (getHeadersThread != null) getHeadersThread.interrupt();
        if (getBodiesThread != null) getBodiesThread.interrupt();
        stopLatch.countDown();
    }

    public void waitForStop() {
        try {
            stopLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void setHeaderQueueLimit(int headerQueueLimit) {
        this.headerQueueLimit = headerQueueLimit;
    }

    public int getBlockQueueLimit() {
        return blockQueueLimit;
    }

    public void setBlockQueueLimit(int blockQueueLimit) {
        this.blockQueueLimit = blockQueueLimit;
    }

    private void headerRetrieveLoop() {
        List<SyncQueueIfc.HeadersRequest> hReq = emptyList();
        while(!Thread.currentThread().isInterrupted()) {
            try {

                if (syncQueue.getHeadersCount() < headerQueueLimit) {
                    if (hReq.isEmpty()) {
                        synchronized (this) {
                            hReq = new ArrayList<>(syncQueue.requestHeaders(MAX_IN_REQUEST, REQUESTS));
                        }
                    }
                    if (hReq.size() == 0) {
                        logger.info("Headers download complete.");
                        headersDownloadComplete = true;
                        if (!blockBodiesDownload) {
                            finishDownload();
                            downloadComplete = true;
                        }
                        return;
                    }
                    int reqHeadersCounter = 0;
                    for (Iterator<SyncQueueIfc.HeadersRequest> it = hReq.iterator(); it.hasNext();) {
                        SyncQueueIfc.HeadersRequest headersRequest = it.next();

                        final Channel any = getAnyPeer();

                        if (any == null) {
                            logger.debug("headerRetrieveLoop: No IDLE peers found");
                            break;
                        } else {
                            logger.debug("headerRetrieveLoop: request headers (" + headersRequest.getStart() + ") from " + any.getNode());
                            ListenableFuture<List<BlockHeader>> futureHeaders = headersRequest.getHash() == null ?
                                    any.getEthHandler().sendGetBlockHeaders(headersRequest.getStart(), headersRequest.getCount(), headersRequest.isReverse()) :
                                    any.getEthHandler().sendGetBlockHeaders(headersRequest.getHash(), headersRequest.getCount(), headersRequest.getStep(), headersRequest.isReverse());
                            if (futureHeaders != null) {
                                Futures.addCallback(futureHeaders, new FutureCallback<List<BlockHeader>>() {
                                    @Override
                                    public void onSuccess(List<BlockHeader> result) {
                                        if (!validateAndAddHeaders(result, any.getNodeId())) {
                                            onFailure(new RuntimeException("Received headers validation failed"));
                                        }
                                    }

                                    @Override
                                    public void onFailure(Throwable t) {
                                        logger.debug("Error receiving headers. Dropping the peer.", t);
                                        any.getEthHandler().dropConnection();
                                    }
                                });
                                it.remove();
                                reqHeadersCounter++;
                            }
                        }
                    }
                    receivedHeadersLatch = new CountDownLatch(max(reqHeadersCounter / 4, 1));
                }  else {
                    receivedHeadersLatch = new CountDownLatch(1);
                    logger.debug("headerRetrieveLoop: HeaderQueue is full");
                }
                receivedHeadersLatch.await(isSyncDone() ? 10000 : 2000, TimeUnit.MILLISECONDS);

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                logger.error("Unexpected: ", e);
            }
        }
    }

    private void blockRetrieveLoop() {
        class BlocksCallback implements FutureCallback<List<Block>> {
            private Channel peer;

            public BlocksCallback(Channel peer) {
                this.peer = peer;
            }

            @Override
            public void onSuccess(List<Block> result) {
                addBlocks(result, peer.getNodeId());
            }

            @Override
            public void onFailure(Throwable t) {
                logger.debug("Error receiving Blocks. Dropping the peer.", t);
                peer.getEthHandler().dropConnection();
            }
        }

        while(!Thread.currentThread().isInterrupted()) {
            try {

                int blocksToAsk = getBlockQueueFreeSize();
                if (blocksToAsk > MAX_IN_REQUEST) {
                    int maxRequests = blocksToAsk / MAX_IN_REQUEST;
                    int maxBlocks = MAX_IN_REQUEST * Math.min(maxRequests, REQUESTS);
                    SyncQueueIfc.BlocksRequest bReq = syncQueue.requestBlocks(maxBlocks);

                    if (bReq.getBlockHeaders().size() == 0 && headersDownloadComplete) {
                        logger.info("Block download complete.");
                        finishDownload();
                        downloadComplete = true;
                        return;
                    }

                    if (bReq.getBlockHeaders().size() <= 3) {
                        // new blocks are better to request from the header senders first
                        // to get more chances to receive block body promptly
                        for (BlockHeaderWrapper blockHeaderWrapper : bReq.getBlockHeaders()) {
                            Channel channel = pool.getByNodeId(blockHeaderWrapper.getNodeId());
                            if (channel != null) {
                                ListenableFuture<List<Block>> futureBlocks =
                                        channel.getEthHandler().sendGetBlockBodies(singletonList(blockHeaderWrapper));
                                if (futureBlocks != null) {
                                    Futures.addCallback(futureBlocks, new BlocksCallback(channel));
                                }
                            }
                        }
                    }

                    int reqBlocksCounter = 0;
                    for (SyncQueueIfc.BlocksRequest blocksRequest : bReq.split(MAX_IN_REQUEST)) {
                        Channel any = getAnyPeer();
                        if (any == null) {
                            logger.debug("blockRetrieveLoop: No IDLE peers found");
                            break;
                        } else {
                            logger.debug("blockRetrieveLoop: Requesting " + blocksRequest.getBlockHeaders().size() + " blocks from " + any.getNode());
                            ListenableFuture<List<Block>> futureBlocks =
                                    any.getEthHandler().sendGetBlockBodies(blocksRequest.getBlockHeaders());
                            if (futureBlocks != null) {
                                Futures.addCallback(futureBlocks, new BlocksCallback(any));
                                reqBlocksCounter++;
                            }
                        }
                    }
                    receivedBlocksLatch = new CountDownLatch(max(reqBlocksCounter, 1));
                } else {
                    logger.debug("blockRetrieveLoop: BlockQueue is full");
                    receivedBlocksLatch = new CountDownLatch(1);
                }
                receivedBlocksLatch.await(2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                logger.error("Unexpected: ", e);
            }
        }
    }

    /**
     * Adds a list of blocks to the queue
     *
     * @param blocks block list received from remote peer and be added to the queue
     * @param nodeId nodeId of remote peer which these blocks are received from
     */
    private void addBlocks(List<Block> blocks, byte[] nodeId) {

        if (blocks.isEmpty()) {
            return;
        }

        synchronized (this) {
            logger.debug("Adding new " + blocks.size() + " blocks to sync queue: " +
                    blocks.get(0).getShortDescr() + " ... " + blocks.get(blocks.size() - 1).getShortDescr());

            List<Block> newBlocks = syncQueue.addBlocks(blocks);

            List<BlockWrapper> wrappers = new ArrayList<>();
            for (Block b : newBlocks) {
                wrappers.add(new BlockWrapper(b, nodeId));
            }


            logger.debug("Pushing " + wrappers.size() + " blocks to import queue: " + (wrappers.isEmpty() ? "" :
                    wrappers.get(0).getBlock().getShortDescr() + " ... " + wrappers.get(wrappers.size() - 1).getBlock().getShortDescr()));

            pushBlocks(wrappers);
        }

        receivedBlocksLatch.countDown();

        if (logger.isDebugEnabled()) logger.debug(
                "Blocks waiting to be proceed: lastBlock.number: [{}]",
                blocks.get(blocks.size() - 1).getNumber()
        );
    }

    /**
     * Adds list of headers received from remote host <br>
     * Runs header validation before addition <br>
     * It also won't add headers of those blocks which are already presented in the queue
     *
     * @param headers list of headers got from remote host
     * @param nodeId remote host nodeId
     *
     * @return true if blocks passed validation and were added to the queue,
     *          otherwise it returns false
     */
    private boolean validateAndAddHeaders(List<BlockHeader> headers, byte[] nodeId) {

        if (headers.isEmpty()) return true;

        List<BlockHeaderWrapper> wrappers = new ArrayList<>(headers.size());

        for (BlockHeader header : headers) {

            if (!isValid(header)) {

                if (logger.isDebugEnabled()) {
                    logger.debug("Invalid header RLP: {}", Hex.toHexString(header.getEncoded()));
                }

                return false;
            }

            wrappers.add(new BlockHeaderWrapper(header, nodeId));
        }

        synchronized (this) {
            List<BlockHeaderWrapper> headersReady = syncQueue.addHeaders(wrappers);
            if (headersReady != null && !headersReady.isEmpty()) {
                pushHeaders(headersReady);
            }
        }

        receivedHeadersLatch.countDown();

        logger.debug("{} headers added", headers.size());

        return true;
    }

    /**
     * Runs checks against block's header. <br>
     * All these checks make sense before block is added to queue
     * in front of checks running by {@link BlockchainImpl#isValid(BlockHeader)}
     *
     * @param header block header
     * @return true if block is valid, false otherwise
     */
    protected boolean isValid(BlockHeader header) {

        if (!headerValidator.validate(header)) {

            headerValidator.logErrors(logger);
            return false;
        }

        return true;
    }

    /**
     * When sync is not done, we are working with several peers, so we have enough randomness.
     * When we switch to short sync, we usually need only one peer, so if choosing
     * the best one, we will get the same one all the time (but we need some randomness)
     */
    private Channel getGoodPeer() {
        return isSyncDone() ? pool.getAnyIdle() : pool.getBestIdle();
    }

    Channel getAnyPeer() {
        return pool.getAnyIdle();
    }

    public boolean isSyncDone() {
        return false;
    }

    public void close() {
        try {
            if (pool != null) pool.close();
            stop();
        } catch (Exception e) {
            logger.warn("Problems closing SyncManager", e);
        }
    }

}
