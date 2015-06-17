/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.fenzo;

import com.netflix.fenzo.functions.Action1;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

class AutoScaler {

    private static class HostAttributeGroup {
        String name;
        List<VirtualMachineLease> idleHosts;
        int shortFall;
        AutoScaleRule rule;

        private HostAttributeGroup(String name, AutoScaleRule rule) {
            this.name = name;
            this.rule = rule;
            this.idleHosts = new ArrayList<>();
            this.shortFall=0;
        }
    }

    private static class ScalingActivity {
        private long scaleUpAt;
        private long scaleDownAt;
        private int shortfall;
        private int scaledNumInstances;
        private AutoScaleAction.Type type;

        private ScalingActivity(long scaleUpAt, long scaleDownAt, int shortfall, int scaledNumInstances, AutoScaleAction.Type type) {
            this.scaleUpAt = scaleUpAt;
            this.scaleDownAt = scaleDownAt;
            this.shortfall = shortfall;
            this.scaledNumInstances = scaledNumInstances;
            this.type = type;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(AutoScaler.class);
    private volatile Action1<AutoScaleAction> callback=null;
    private final String mapHostnameAttributeName;
    private final String scaleDownBalancedByAttributeName;
    private ShortfallEvaluator shortfallEvaluator;
    private final ActiveVmGroups activeVmGroups;
    private final AutoScaleRules autoScaleRules;
    private final boolean disableShortfallEvaluation;
    private final String attributeName;
    private final AssignableVMs assignableVMs;
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(1, 1, Long.MAX_VALUE, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(100),
                     new ThreadPoolExecutor.DiscardOldestPolicy());
    final ConcurrentMap<String, ScalingActivity> scalingActivityMap = new ConcurrentHashMap<>();

    AutoScaler(final String attributeName, String mapHostnameAttributeName, String scaleDownBalancedByAttributeName,
               final List<AutoScaleRule> autoScaleRules,
               final AssignableVMs assignableVMs, TaskScheduler phantomTaskScheduler,
               final boolean disableShortfallEvaluation, ActiveVmGroups activeVmGroups) {
        this.mapHostnameAttributeName = mapHostnameAttributeName;
        this.scaleDownBalancedByAttributeName = scaleDownBalancedByAttributeName;
        this.shortfallEvaluator = new ShortfallEvaluator(phantomTaskScheduler);
        this.attributeName = attributeName;
        this.autoScaleRules = new AutoScaleRules(autoScaleRules);
        this.assignableVMs = assignableVMs;
        this.disableShortfallEvaluation = disableShortfallEvaluation;
//        autoScalerInputObservable
//                .onErrorResumeNext(new Func1<Throwable, Observable<? extends AutoScalerInput>>() {
//                    @Override
//                    public Observable<? extends AutoScalerInput> call(Throwable throwable) {
//                        logger.warn("Error in auto scaler handler: " + throwable.getMessage(), throwable);
//                        return Observable.empty();
//                    }
//                })
//                .flatMap(new Func1<AutoScalerInput, Observable<HostAttributeGroup>>() {
//                    @Override
//                    public Observable<HostAttributeGroup> call(AutoScalerInput autoScalerInput) {
//                        Map<String, HostAttributeGroup> hostAttributeGroupMap = setupHostAttributeGroupMap(autoScaleRules, scalingActivityMap);
//                        if(!disableShortfallEvaluation) {
//                            Map<String, Integer> shortfall = shortfallEvaluator.getShortfall(hostAttributeGroupMap.keySet(), autoScalerInput.getFailures());
//                            for (Map.Entry<String, Integer> entry : shortfall.entrySet()) {
//                                hostAttributeGroupMap.get(entry.getKey()).shortFall = entry.getValue() == null ? 0 : entry.getValue();
//                            }
//                        }
//                        populateIdleResources(autoScalerInput.getIdleResourcesList(), hostAttributeGroupMap, attributeName);
//                        return Observable.from(hostAttributeGroupMap.values());
//                    }
//                })
//                .onErrorResumeNext(new Func1<Throwable, Observable<? extends HostAttributeGroup>>() {
//                    @Override
//                    public Observable<? extends HostAttributeGroup> call(Throwable throwable) {
//                        logger.warn("Error in autoscaler: " + throwable.getMessage(), throwable);
//                        return Observable.empty();
//                    }
//                })
//                .doOnNext(new Action1<HostAttributeGroup>() {
//                    @Override
//                    public void call(HostAttributeGroup hostAttributeGroup) {
//                        processScalingNeeds(hostAttributeGroup, scalingActivityMap, assignableVMs);
//                    }
//                })
//                .subscribe();
        this.activeVmGroups = activeVmGroups;
    }

    Collection<AutoScaleRule> getRules() {
        return Collections.unmodifiableCollection(autoScaleRules.getRules());
    }

    void replaceRule(AutoScaleRule rule) {
        if(rule == null)
            throw new NullPointerException("Can't add null rule");
        autoScaleRules.replaceRule(rule);
    }

    void removeRule(String ruleName) {
        if(ruleName != null)
            autoScaleRules.remRule(ruleName);
    }

    void scheduleAutoscale(final AutoScalerInput autoScalerInput) {
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    autoScaleRules.prepare();
                    Map<String, HostAttributeGroup> hostAttributeGroupMap = setupHostAttributeGroupMap(autoScaleRules, scalingActivityMap);
                    if (!disableShortfallEvaluation) {
                        Map<String, Integer> shortfall = shortfallEvaluator.getShortfall(hostAttributeGroupMap.keySet(), autoScalerInput.getFailures());
                        for (Map.Entry<String, Integer> entry : shortfall.entrySet()) {
                            hostAttributeGroupMap.get(entry.getKey()).shortFall = entry.getValue() == null ? 0 : entry.getValue();
                        }
                    }
                    populateIdleResources(autoScalerInput.getIdleResourcesList(), hostAttributeGroupMap, attributeName);
                    for (HostAttributeGroup hostAttributeGroup : hostAttributeGroupMap.values()) {
                        processScalingNeeds(hostAttributeGroup, scalingActivityMap, assignableVMs);
                    }
                }
            });
        }
        catch (RejectedExecutionException e) {
            logger.warn("Autoscaler execution request rejected: " + e.getMessage());
        }
    }

    private boolean shouldScaleNow(boolean scaleUp, long now, ScalingActivity prevScalingActivity, AutoScaleRule rule) {
        return scaleUp?
                now > (Math.max(activeVmGroups.getLastSetAt(), prevScalingActivity.scaleUpAt) + rule.getCoolDownSecs() * 1000) :
                now > (Math.max(activeVmGroups.getLastSetAt(), Math.max(prevScalingActivity.scaleDownAt, prevScalingActivity.scaleUpAt))
                        + rule.getCoolDownSecs() * 1000);
    }

    private boolean shouldScaleUp(long now, ScalingActivity prevScalingActivity, AutoScaleRule rule) {
        return shouldScaleNow(true, now, prevScalingActivity, rule);
    }

    private boolean shouldScaleDown(long now, ScalingActivity prevScalingActivity, AutoScaleRule rule) {
        return shouldScaleNow(false, now, prevScalingActivity, rule);
    }

    private void processScalingNeeds(HostAttributeGroup hostAttributeGroup, ConcurrentMap<String, ScalingActivity> scalingActivityMap, AssignableVMs assignableVMs) {
        AutoScaleRule rule = hostAttributeGroup.rule;
        long now = System.currentTimeMillis();
        ScalingActivity prevScalingActivity= scalingActivityMap.get(rule.getRuleName());
        int excess = hostAttributeGroup.shortFall>0? 0 : hostAttributeGroup.idleHosts.size() - rule.getMaxIdleHostsToKeep();
        if (excess > 0 && shouldScaleDown(now, prevScalingActivity, rule)) {
            Map<String, String> hostsToTerminate = getHostsToTerminate(hostAttributeGroup.idleHosts, excess);
            ScalingActivity scalingActivity = scalingActivityMap.get(rule.getRuleName());
            scalingActivity.scaleDownAt = now;
            scalingActivity.shortfall = hostAttributeGroup.shortFall;
            scalingActivity.scaledNumInstances = hostsToTerminate.size();
            scalingActivity.type = AutoScaleAction.Type.Down;
            StringBuilder sBuilder = new StringBuilder();
            for (String host : hostsToTerminate.keySet()) {
                sBuilder.append(host).append(", ");
                assignableVMs.disableUntil(host, now+rule.getCoolDownSecs()*1000);
            }
            logger.info("Scaling down " + rule.getRuleName() + " by "
                    + excess + " hosts (" + sBuilder.toString() + ")");
            callback.call(
                    new ScaleDownAction(rule.getRuleName(), hostsToTerminate.values())
            );
        } else if(hostAttributeGroup.shortFall>0 || (excess<=0 && shouldScaleUp(now, prevScalingActivity, rule))) {
            if (hostAttributeGroup.shortFall>0 || rule.getMinIdleHostsToKeep() > hostAttributeGroup.idleHosts.size()) {
                // scale up to rule.getMaxIdleHostsToKeep() instead of just until rule.getMinIdleHostsToKeep()
                // but, if not shouldScaleUp(), then, scale up due to shortfall
                int shortage = (excess<=0 && shouldScaleUp(now, prevScalingActivity, rule))?
                        rule.getMaxIdleHostsToKeep() - hostAttributeGroup.idleHosts.size() :
                        0;
                shortage = Math.max(shortage, hostAttributeGroup.shortFall);
                ScalingActivity scalingActivity = scalingActivityMap.get(rule.getRuleName());
                scalingActivity.scaleUpAt = now;
                scalingActivity.shortfall = hostAttributeGroup.shortFall;
                scalingActivity.scaledNumInstances = shortage;
                scalingActivity.type = AutoScaleAction.Type.Up;
                logger.info("Scaling up " + rule.getRuleName() + " by "
                        + shortage + " hosts");
                callback.call(
                        new ScaleUpAction(rule.getRuleName(), getEffectiveShortage(shortage, scalingActivity.shortfall))
                );
            }
        }
    }

    private int getEffectiveShortage(int shortage, int shortfall) {
        return Math.max(shortage, shortfall);
    }

    private void populateIdleResources(List<VirtualMachineLease> idleResources, Map<String, HostAttributeGroup> leasesMap, String attributeName) {
        for (VirtualMachineLease l : idleResources) {
            if (l.getAttributeMap() != null && l.getAttributeMap().get(attributeName) != null &&
                    l.getAttributeMap().get(attributeName).getText().hasValue()) {
                String attrValue = l.getAttributeMap().get(attributeName).getText().getValue();
                if (leasesMap.containsKey(attrValue)) {
                    if (!leasesMap.get(attrValue).rule.idleMachineTooSmall(l))
                        leasesMap.get(attrValue).idleHosts.add(l);
                }
            }
        }
    }

    private Map<String, HostAttributeGroup> setupHostAttributeGroupMap(AutoScaleRules autoScaleRules, ConcurrentMap<String, ScalingActivity> lastScalingAt) {
        Map<String, HostAttributeGroup> leasesMap = new HashMap<>();
        for (AutoScaleRule rule : autoScaleRules.getRules()) {
            leasesMap.put(rule.getRuleName(),
                    new HostAttributeGroup(rule.getRuleName(), rule));
            long initialCoolDown = getInitialCoolDown(rule.getCoolDownSecs());
            lastScalingAt.putIfAbsent(rule.getRuleName(), new ScalingActivity(initialCoolDown, initialCoolDown, 0, 0, null));
        }
        return leasesMap;
    }

    // make scaling activity happen after a fixed delayed time for the first time encountered (e.g., server start)
    private long getInitialCoolDown(long coolDownSecs) {
        long initialCoolDownInPastSecs=120;
        initialCoolDownInPastSecs = Math.min(coolDownSecs, initialCoolDownInPastSecs);
        return System.currentTimeMillis()- coolDownSecs*1000 + initialCoolDownInPastSecs*1000;
    }

    private Map<String, String> getHostsToTerminate(List<VirtualMachineLease> hosts, int excess) {
        Map<String, String> result = new HashMap<>();
        final Map<String, List<VirtualMachineLease>> hostsMap = new HashMap<>();
        final String defaultAttributeName = "default";
        for(VirtualMachineLease host: hosts) {
            final Protos.Attribute attribute = host.getAttributeMap().get(scaleDownBalancedByAttributeName);
            String val = (attribute!=null && attribute.hasText())? attribute.getText().getValue() : defaultAttributeName;
            if(hostsMap.get(val) == null)
                hostsMap.put(val, new ArrayList<VirtualMachineLease>());
            hostsMap.get(val).add(host);
        }
        final List<List<VirtualMachineLease>> lists = new ArrayList<>();
        for(List<VirtualMachineLease> l: hostsMap.values())
            lists.add(l);
        int taken=0;
        while(taken<excess) {
            List<VirtualMachineLease> takeFrom=null;
            int max=0;
            for(List<VirtualMachineLease> l: lists) {
                if(l.size()>max) {
                    max = l.size();
                    takeFrom = l;
                }
            }
            final VirtualMachineLease removed = takeFrom.remove(0);
            result.put(removed.hostname(), getMappedHostname(removed));
            taken++;
        }
        return result;
    }

    private String getMappedHostname(VirtualMachineLease lease) {
        if(mapHostnameAttributeName==null || mapHostnameAttributeName.isEmpty())
            return lease.hostname();
        Protos.Attribute attribute = lease.getAttributeMap().get(mapHostnameAttributeName);
        if(attribute==null) {
            logger.error("Didn't find attribute " + mapHostnameAttributeName + " for host " + lease.hostname());
            return lease.hostname();
        }
        return attribute.getText().getValue();
    }

    public void setCallback(Action1<AutoScaleAction> callback) {
        this.callback = callback;
    }
}
