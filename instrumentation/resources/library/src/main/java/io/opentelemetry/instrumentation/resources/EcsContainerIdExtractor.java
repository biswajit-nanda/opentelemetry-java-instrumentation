package io.opentelemetry.instrumentation.resources;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Optional.empty;

public class EcsContainerIdExtractor {
  private static final Logger logger =
      Logger.getLogger(EcsContainerIdExtractor.class.getName());

  // The file /proc/1/cpuset is present in any Amazon ECS Container, irrespective of whether it is Fargate or EC2.
  static final Path ECS_CGROUP_PATH = Paths.get("/proc/1/cpuset");
  // ContainerID regex for ECS-Fargate: [0-9a-f]{32}-[0-9]+$
  // ContainerID regex for ECS-EC2: [0-9a-f]{64}$
  private static final Pattern CONTAINER_ID_RE = Pattern.compile("[0-9a-f]{32}-[0-9]+$|[0-9a-f]{64}$");

  private final ContainerResource.Filesystem filesystem;

  EcsContainerIdExtractor() {
    this(ContainerResource.FILESYSTEM_INSTANCE);
  }

  // Exists for testing
  EcsContainerIdExtractor(ContainerResource.Filesystem filesystem) {
    this.filesystem = filesystem;
  }

  Optional<String> extractContainerId() {
    if (!filesystem.isReadable(ECS_CGROUP_PATH)) {
      return empty();
    }
    try {
      Optional<String> containerID = filesystem
          .lines(ECS_CGROUP_PATH)
          .flatMap(line -> Stream.of(line.split("/")))
          .map(CONTAINER_ID_RE::matcher)
          .filter(Matcher::matches)
          .findFirst()
          .map(matcher -> matcher.group(0));
      logger.log(Level.INFO, "ContainerID from ECS: " + containerID);
      return containerID;
    } catch (IOException e) {
      logger.log(Level.WARNING, "Unable to read ECS cgroup path", e);
    }
    return empty();
  }
}
