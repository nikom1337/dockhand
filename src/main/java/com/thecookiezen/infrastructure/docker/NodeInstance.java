package com.thecookiezen.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.async.ResultCallbackTemplate;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.thecookiezen.bussiness.cluster.boundary.ContainerFetcher;
import com.thecookiezen.bussiness.cluster.entity.HostInfo;
import com.thecookiezen.bussiness.cluster.entity.StatisticsLite;
import com.thecookiezen.bussiness.deployment.control.ProgressDetail;
import com.thecookiezen.bussiness.jobs.entity.Job;
import com.thecookiezen.bussiness.jobs.entity.Task;
import lombok.Data;
import lombok.extern.apachecommons.CommonsLog;
import rx.Emitter;
import rx.Observable;

import java.io.IOException;
import java.util.Collection;
import java.util.stream.Collectors;

@Data
@CommonsLog
public class NodeInstance implements ContainerFetcher {

    private final long id;

    private final String name;

    private final DockerClient dockerClient;

    @Override
    public Collection<Container> getContainers() {
        return dockerClient.listContainersCmd().withShowAll(true).exec();
    }

    @Override
    public HostInfo getInfo() {
        Info info = dockerClient.infoCmd().exec();
        return new HostInfo(
                info.getName(),
                "",
                info.getOperatingSystem(),
                info.getServerVersion(),
                info.getContainers(),
                info.getContainersStopped(),
                info.getContainersPaused(),
                info.getContainersRunning(),
                info.getMemTotal(),
                info.getKernelVersion()
        );
    }

    public InspectContainerResponse getContainer(String containerId) {
        return dockerClient.inspectContainerCmd(containerId).exec();
    }

    @Override
    public Observable<StatisticsLite> stats(String containerId) {
        StatsCmd statsCmd = dockerClient.statsCmd(containerId);
        return Observable.fromEmitter(statsEmitter -> {
            final ResultCallback resultCallback = new ResultCallbackTemplate<ResultCallback<Statistics>, Statistics>() {
                @Override
                public void onNext(Statistics statistics) {
                    statsEmitter.onNext(new StatisticsLite(statistics));
                }
            };
            statsEmitter.setCancellation(resultCallback::onComplete);
            statsCmd.exec(resultCallback);
        }, Emitter.BackpressureMode.BUFFER);
    }

    @Override
    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse exec = dockerClient.inspectContainerCmd(containerId).exec();
            if (exec == null) {
                return false;
            }
            return exec.getState().getRunning();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Observable<String> logs(String containerId) {
        LogContainerCmd logContainerCmd = dockerClient.logContainerCmd(containerId)
                .withFollowStream(true)
                .withTailAll()
                .withStdErr(true)
                .withStdOut(true);

        return Observable.fromEmitter(stringEmitter -> {
            final LogContainerResultCallback logContainerResultCallback = new LogContainerResultCallback() {
                @Override
                public void onNext(Frame item) {
                    stringEmitter.onNext(new String(item.getPayload()));
                }
            };
            stringEmitter.setCancellation(logContainerResultCallback::onComplete);
            logContainerCmd.exec(logContainerResultCallback);
        }, Emitter.BackpressureMode.BUFFER);
    }

    @Override
    public void close() {
        try {
            this.getDockerClient().close();
        } catch (IOException e) {
            log.error("Error occurred during closing docker client for node [" + getName() + "]");
        }
    }

    @Override
    public Observable<ProgressDetail> deploy(Job job) {
        return Observable.fromEmitter(eventsEmitter -> {
            job.getTasks().forEach(task -> {
                String tasks = "Task[" + 0 + "/" + job.getTasks().size() + "]";

                dockerClient.pullImageCmd(task.getImage())
                        .withTag(task.getImageVersion())
                        .exec(new PullImageResultCallback() {
                            @Override
                            public void onNext(PullResponseItem item) {
                                super.onNext(item);
                                eventsEmitter.onNext(new ProgressDetail(tasks + " " + item.getStatus()));
                            }
                        }).awaitSuccess();

                String containerId = getCreateContainerCmd(task).exec().getId();
                dockerClient.startContainerCmd(containerId).exec();
                eventsEmitter.onNext(new ProgressDetail(tasks + task.getName() + " started."));
            });
            eventsEmitter.onCompleted();
        }, Emitter.BackpressureMode.BUFFER);
    }

    private CreateContainerCmd getCreateContainerCmd(Task task) {
        return dockerClient.createContainerCmd(task.getImage())
                .withEnv(task.getEnvs().stream().map(e -> e.getName() + "=" + e.getValue()).collect(Collectors.toList()))
                .withName(task.getName());
    }
}
