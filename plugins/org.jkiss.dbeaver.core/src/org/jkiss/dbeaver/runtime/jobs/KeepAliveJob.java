/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2017 Serge Rider (serge@jkiss.org)
 *
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
 */
package org.jkiss.dbeaver.runtime.jobs;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.registry.ProjectRegistry;

import java.util.*;

/**
 * KeepAliveJob
 */
public class KeepAliveJob extends AbstractJob
{
    public static final int MONITOR_INTERVAL = 5000; // once per 5 seconds

    private static final Log log = Log.getLog(KeepAliveJob.class);

    private Map<String, Long> checkCache = new HashMap<>();
    private final Set<String> pingCache = new HashSet<>();

    public KeepAliveJob()
    {
        super("Keep-Alive monitor");
        setUser(false);
        setSystem(true);
    }

    @Override
    protected IStatus run(DBRProgressMonitor monitor)
    {
        if (DBeaverCore.isClosing()) {
            return Status.OK_STATUS;
        }
        final DBeaverCore core = DBeaverCore.getInstance();
        final ProjectRegistry projectRegistry = core.getProjectRegistry();
        if (projectRegistry == null) {
            return Status.OK_STATUS;
        }
        for (DataSourceDescriptor ds : DataSourceRegistry.getAllDataSources()) {
            checkDataSourceAlive(monitor, ds);
        }
        if (!DBeaverCore.isClosing()) {
            scheduleMonitor();
        }
        return Status.OK_STATUS;
    }

    private void checkDataSourceAlive(DBRProgressMonitor monitor, final DataSourceDescriptor dataSourceDescriptor) {
        if (!dataSourceDescriptor.isConnected()) {
            return;
        }
        final int keepAliveInterval = dataSourceDescriptor.getConnectionConfiguration().getKeepAliveInterval();
        if (keepAliveInterval <= 0) {
            return;
        }
        final String dsId = dataSourceDescriptor.getId();
        synchronized (this) {
            if (pingCache.contains(dsId)) {
                // Ping is still in progress. Hanged?
                // Anyway - just skip it
                return;
            }
        }
        final DBPDataSource dataSource = dataSourceDescriptor.getDataSource();
        if (dataSource == null) {
            return;
        }
        Long lastCheckTime;
        synchronized (this) {
            lastCheckTime = checkCache.get(dsId);
        }
        if (lastCheckTime == null) {
            final Date connectTime = dataSourceDescriptor.getConnectTime();
            if (connectTime != null) {
                lastCheckTime = connectTime.getTime();
            }
        }
        if (lastCheckTime == null) {
            log.debug("Can't determine last check time for " + dsId);
            return;
        }
        long curTime = System.currentTimeMillis();
        if ((curTime - lastCheckTime) / 1000 > keepAliveInterval) {
            final PingJob pingJob = new PingJob(dataSource);
            pingJob.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    synchronized (KeepAliveJob.this) {
                        checkCache.put(dsId, System.currentTimeMillis());
                        pingCache.remove(dsId);
                    }
                }
            });
            synchronized (this) {
                pingCache.add(dsId);
            }
            pingJob.schedule();
        }
    }

    public void scheduleMonitor() {
        schedule(MONITOR_INTERVAL);
    }

}