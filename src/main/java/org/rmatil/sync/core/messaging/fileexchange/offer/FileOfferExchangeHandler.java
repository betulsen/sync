package org.rmatil.sync.core.messaging.fileexchange.offer;

import net.engio.mbassy.bus.MBassador;
import org.rmatil.sync.core.eventbus.IBusEvent;
import org.rmatil.sync.core.eventbus.IgnoreBusEvent;
import org.rmatil.sync.core.init.client.ILocalStateResponseCallback;
import org.rmatil.sync.core.messaging.StatusCode;
import org.rmatil.sync.event.aggregator.core.events.IEvent;
import org.rmatil.sync.event.aggregator.core.events.MoveEvent;
import org.rmatil.sync.network.api.INode;
import org.rmatil.sync.network.api.INodeManager;
import org.rmatil.sync.network.api.IRequest;
import org.rmatil.sync.network.api.IResponse;
import org.rmatil.sync.network.core.ANetworkHandler;
import org.rmatil.sync.network.core.model.ClientDevice;
import org.rmatil.sync.network.core.model.NodeLocation;
import org.rmatil.sync.persistence.core.tree.ITreeStorageAdapter;
import org.rmatil.sync.persistence.exceptions.InputOutputException;
import org.rmatil.sync.version.api.AccessType;
import org.rmatil.sync.version.api.IObjectStore;
import org.rmatil.sync.version.api.PathType;
import org.rmatil.sync.version.core.model.PathObject;
import org.rmatil.sync.version.core.model.Sharer;
import org.rmatil.sync.version.core.model.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Tries to determine whether a particular {@link IEvent} resp. {@link SerializableEvent}
 * causes a conflict on any connected client.
 * This is done, by sending {@link FileOfferRequest} to all connected clients. These
 * determine whether the event causes a conflict and response with the appropriate answer.
 * If a conflict is detected, a {@link FileOfferExchangeHandlerResult} is returned having
 * the fields for conflict set to true.
 */
public class FileOfferExchangeHandler extends ANetworkHandler<FileOfferExchangeHandlerResult> implements ILocalStateResponseCallback {

    private static final Logger logger = LoggerFactory.getLogger(FileOfferExchangeHandler.class);

    /**
     * The id of the file exchange
     */
    protected UUID exchangeId;

    /**
     * A storage adapter to access the synchronized folder
     */
    protected INodeManager nodeManager;

    /**
     * The client device information
     */
    protected ClientDevice clientDevice;

    /**
     * The actual event to check for conflicts on other clients
     */
    protected IEvent eventToPropagate;

    /**
     * A list of clients which responded to the file offer request
     */
    protected List<IResponse> respondedClients;

    /**
     * The object store to access the file versions
     */
    protected IObjectStore objectStore;

    /**
     * The storage adapter for the synchronized folder
     */
    protected ITreeStorageAdapter storageAdapter;

    /**
     * The global bus event used for adding events
     */
    protected MBassador<IBusEvent> globalEventBus;

    /**
     * @param exchangeId       The exchange id used for the file offer handling
     * @param clientDevice     The client device used to identify the sending client for any file offer requests
     * @param nodeManager      The client manager to access client locations
     * @param client           The client to send messages
     * @param objectStore      The object store to get versions of a particular file
     * @param storageAdapter   The storage adapter for the synchronized folder
     * @param globalEventBus   The global event bus to add events to
     * @param eventToPropagate The actual event to check for conflicts
     */
    public FileOfferExchangeHandler(UUID exchangeId, ClientDevice clientDevice, INodeManager nodeManager, INode client, IObjectStore objectStore, ITreeStorageAdapter storageAdapter, MBassador<IBusEvent> globalEventBus, IEvent eventToPropagate) {
        super(client);
        this.clientDevice = clientDevice;
        this.exchangeId = exchangeId;
        this.nodeManager = nodeManager;
        this.objectStore = objectStore;
        this.storageAdapter = storageAdapter;
        this.globalEventBus = globalEventBus;
        this.eventToPropagate = eventToPropagate;
        this.respondedClients = new ArrayList<>();
    }

    @Override
    public void run() {
        try {
            String pathToCheck = this.eventToPropagate.getEventName().equals(MoveEvent.EVENT_NAME) ? ((MoveEvent) this.eventToPropagate).getNewPath().toString() : this.eventToPropagate.getPath().toString();

            PathObject pathObject;

            try {
                pathObject = this.objectStore.getObjectManager().getObjectForPath(pathToCheck);
            } catch (InputOutputException e) {
                logger.error("Can not read path object from object store. Message: " + e.getMessage() + ". Aborting file offer exchange " + this.exchangeId);
                return;
            }

            // do not check using storage adapter since the file could've been deleted already
            // -> offering for file delete
            boolean isDir = pathObject.getPathType().equals(PathType.DIRECTORY);

            // since this sync is triggered by a move, the actual operation is already
            // done on this client, therefore we traverse the dir on the new path
            if (isDir && this.eventToPropagate instanceof MoveEvent) {
                // we only offer the move event from the "root" directory
                Path dirToMove = Paths.get(this.storageAdapter.getRootDir().getPath()).resolve(((MoveEvent) this.eventToPropagate).getNewPath());
                try (Stream<Path> paths = Files.walk(dirToMove)) {
                    paths.forEach((entry) -> {
                        // do not use toAbsolutePath() since we could have also paths starting with "./myDir"
                        Path relPath = Paths.get(this.storageAdapter.getRootDir().getPath()).relativize(entry);
                        Path oldPath = this.eventToPropagate.getPath().resolve(((MoveEvent) this.eventToPropagate).getNewPath().relativize(relPath));

                        // move also file id
                        try {
                            this.node.getIdentifierManager().moveKey(oldPath.toString(), relPath.toString());
                        } catch (InputOutputException e) {
                            logger.warn("Failed to move file id for file " + oldPath.toString() + ". Message: " + e.getMessage());
                        }

                        globalEventBus.publish(new IgnoreBusEvent(
                                new MoveEvent(
                                        oldPath,
                                        relPath,
                                        entry.getFileName().toString(),
                                        "weIgnoreTheHash",
                                        System.currentTimeMillis()
                                )
                        ));
                    });
                } catch (IOException e) {
                    logger.error("Could not create ignore events for moving " + this.eventToPropagate.getPath().toString() + " to " + ((MoveEvent) this.eventToPropagate).getNewPath().toString() + ". Message: " + e.getMessage());
                }
            }

            // Fetch client locations from the DHT
            List<NodeLocation> clientLocations;
            try {
                clientLocations = this.nodeManager.getNodeLocations(super.node.getUser().getUserName());
            } catch (InputOutputException e) {
                logger.error("Could not fetch client locations from user " + super.node.getUser().getUserName() + ". Message: " + e.getMessage());
                return;
            }

            // send changes back to owner too, if we have write access
            // and the owner is not the user from this client
            UUID fileId = null;
            String owner = null;
            if (null != pathObject.getOwner() &&
                    ! this.node.getUser().getUserName().equals(pathObject.getOwner()) &&
                    AccessType.WRITE.equals(pathObject.getAccessType())) {
                // we got write permissions, so we send the changes also back to the original owner of the file
                try {
                    List<NodeLocation> ownerLocations = this.nodeManager.getNodeLocations(pathObject.getOwner());

                    // only add one client of the owner. He may propagate the change then
                    // to his own clients. Conflicts get detected there, if any
                    if (! ownerLocations.isEmpty()) {
                        clientLocations.add(ownerLocations.get(0));
                    }

                    fileId = super.node.getIdentifierManager().getValue(pathToCheck);
                    owner = pathObject.getOwner();

                } catch (InputOutputException e) {
                    logger.error("Could not fetch client locations of owner " + pathObject.getOwner() + " for file " + pathObject.getAbsolutePath() + ". Will therefore skip to notify his clients.");
                }
            }

            if (pathObject.isShared()) {
                for (Sharer entry : pathObject.getSharers()) {
                    try {
                        // ask sharer's clients to get the changes too
                        List<NodeLocation> sharerLocations = this.nodeManager.getNodeLocations(entry.getUsername());

                        // only add one client of the sharer. He may propagate the change then
                        // to his clients, and if a conflict occurs, there will be a new file
                        if (! sharerLocations.isEmpty()) {
                            fileId = super.node.getIdentifierManager().getValue(pathToCheck);
                            clientLocations.add(sharerLocations.get(0));
                        }
                    } catch (InputOutputException e) {
                        logger.error("Could not get client locations of sharer " + entry.getUsername() + ". Skipping this sharer's clients");
                    }
                }
            }

            Version versionBefore = null;
            // we check versions only for files
            if (! isDir) {
                // get version before the one we got from the event to propagate
                for (Version entry : pathObject.getVersions()) {
                    if (entry.getHash().equals(this.eventToPropagate.getHash())) {
                        // versionBefore contains now the version before this element
                        break;
                    }

                    versionBefore = entry;
                }
            }

            IRequest request = new FileOfferRequest(
                    this.exchangeId,
                    StatusCode.NONE,
                    this.clientDevice,
                    fileId,
                    owner,
                    SerializableEvent.fromEvent(this.eventToPropagate, (null != versionBefore) ? versionBefore.getHash() : null, ! isDir),
                    clientLocations
            );

            super.sendRequest(request);
        } catch (Exception e) {
            logger.error("Got exception in FileOfferExchangeHandler for exchange " + this.exchangeId + ". Message: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> getAffectedFilePaths() {
        List<String> affectedFiles = new ArrayList<>();
        affectedFiles.add(this.eventToPropagate.getPath().toString());
        return affectedFiles;
    }

    @Override
    public void onResponse(IResponse response) {
        if (! (response instanceof FileOfferResponse)) {
            logger.error("Expected response to be instance of " + FileOfferResponse.class.getName() + " but got " + response.getClass().getName());
            return;
        }

        this.respondedClients.add(response);
        super.onResponse(response);
    }

    @Override
    public FileOfferExchangeHandlerResult getResult() {
        ArrayList<FileOfferResponse> fileOfferResponses = new ArrayList<>();
        for (IResponse response : this.respondedClients) {
            if (! (response instanceof FileOfferResponse)) {
                logger.warn("Received unknown response from client " + response.getClientDevice().getClientDeviceId() + " (" + response.getClientDevice().getPeerAddress().inetAddress().getHostAddress() + ":" + response.getClientDevice().getPeerAddress().tcpPort() + ")");
                continue;
            }

            fileOfferResponses.add((FileOfferResponse) response);
        }

        return new FileOfferExchangeHandlerResult(fileOfferResponses);
    }
}
