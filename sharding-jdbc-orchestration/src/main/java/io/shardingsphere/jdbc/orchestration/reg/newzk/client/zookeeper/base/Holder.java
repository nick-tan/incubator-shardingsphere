/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.jdbc.orchestration.reg.newzk.client.zookeeper.base;

import com.google.common.base.Strings;
import io.shardingsphere.jdbc.orchestration.reg.newzk.client.utility.ZookeeperConstants;
import io.shardingsphere.jdbc.orchestration.reg.newzk.client.zookeeper.section.ZookeeperEventListener;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

/*
 * Zookeeper connection holder.
 *
 * @author lidongbo
 */
@Slf4j
public class Holder {
    
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    
    @Getter(value = AccessLevel.PROTECTED)
    private final BaseContext context;
    
    @Getter
    private ZooKeeper zooKeeper;
    
    @Getter
    @Setter(value = AccessLevel.PROTECTED)
    private boolean connected;
    
    Holder(final BaseContext context) {
        this.context = context;
    }
    
    /**
     * Start.
     *
     * @throws IOException IO Exception
     * @throws InterruptedException InterruptedException
     */
    public void start() throws IOException, InterruptedException {
        initZookeeper();
        connectLatch.await();
    }
    
    protected void start(final int wait, final TimeUnit units) throws IOException, InterruptedException {
        initZookeeper();
        connectLatch.await(wait, units);
    }
    
    protected void initZookeeper() throws IOException {
        log.debug("Holder servers:{},sessionTimeOut:{}", context.getServers(), context.getSessionTimeOut());
        zooKeeper = new ZooKeeper(context.getServers(), context.getSessionTimeOut(), startWatcher());
        if (!Strings.isNullOrEmpty(context.getScheme())) {
            zooKeeper.addAuthInfo(context.getScheme(), context.getAuth());
            log.debug("Holder scheme:{},auth:{}", context.getScheme(), context.getAuth());
        }
    }
    
    private Watcher startWatcher() {
        return new Watcher() {
            
            public void process(final WatchedEvent event) {
                log.debug("event-----------:" + event.toString());
                processConnection(event);
                if (!isConnected()) {
                    return;
                }
                processGlobalListener(event);
                // todo filter event type or path
                if (event.getType() == Event.EventType.None) {
                    return;
                }
                if (Event.EventType.NodeDeleted == event.getType() || checkPath(event.getPath())) {
                    processUsualListener(event);
                }
            }
        };
    }
    
    protected void processConnection(final WatchedEvent event) {
        log.debug("BaseClient process event:{}", event.toString());
        if (Watcher.Event.EventType.None == event.getType()) {
            if (Watcher.Event.KeeperState.SyncConnected == event.getState()) {
                connectLatch.countDown();
                connected = true;
                log.debug("BaseClient startWatcher SyncConnected");
            } else if (Watcher.Event.KeeperState.Expired == event.getState()) {
                connected = false;
                try {
                    log.warn("startWatcher Event.KeeperState.Expired");
                    reset();
                    // CHECKSTYLE:OFF
                } catch (final Exception ex) {
                    // CHECKSTYLE:ON
                    log.error("event state Expired:{}", ex.getMessage(), ex);
                }
            } else if (Watcher.Event.KeeperState.Disconnected == event.getState()) {
                connected = false;
            }
        }
    }
    
    private void processGlobalListener(final WatchedEvent event) {
        if (context.getGlobalZookeeperEventListener() != null) {
            context.getGlobalZookeeperEventListener().process(event);
            log.debug("Holder {} process", ZookeeperConstants.GLOBAL_LISTENER_KEY);
        }
    }
    
    private void processUsualListener(final WatchedEvent event) {
        if (!context.getWatchers().isEmpty()) {
            for (ZookeeperEventListener zookeeperEventListener : context.getWatchers().values()) {
                if (zookeeperEventListener.getPath() == null || event.getPath().startsWith(zookeeperEventListener.getPath())) {
                    log.debug("listener process:{}, listener:{}", zookeeperEventListener.getPath(), zookeeperEventListener.getKey());
                    zookeeperEventListener.process(event);
                }
            }
        }
    }
    
    private boolean checkPath(final String path) {
        try {
            return zooKeeper.exists(path, true) != null;
        } catch (final KeeperException | InterruptedException ex) {
            // ignore
        }
        return false;
    }
    
    /**
     * Reset connection.
     *
     * @throws IOException IO Exception
     * @throws InterruptedException InterruptedException
     */
    public void reset() throws IOException, InterruptedException {
        log.debug("zk reset....................................");
        close();
        start();
        log.debug("....................................zk reset");
    }
    
    /**
     * Close.
     */
    public void close() {
        try {
            zooKeeper.register(new Watcher() {
                
                @Override
                public void process(final WatchedEvent watchedEvent) {
        
                }
            });
            zooKeeper.close();
            connected = false;
            log.debug("zk closed");
            this.context.close();
            // CHECKSTYLE:OFF
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            log.warn("Holder close:{}", ex.getMessage());
        }
    }
}
