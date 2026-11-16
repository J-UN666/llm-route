package cn.swjtu.zhixing.journey;

import java.nio.file.Files;
import java.nio.file.Path;

/** 普通行程测试用仓库根目录定位（与 campus 测试同一约定，独立于 campus 测试包可见性）。 */
final class JourneyTestDataRoot {

    private JourneyTestDataRoot() {
    }

    static Path repoRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            boolean hasCampusSources = Files.isDirectory(directory.resolve(Path.of("source-data", "校园数据")));
            boolean hasCampusWebData = Files.isRegularFile(directory.resolve("web/data/campus.json"));
            if (hasCampusSources && hasCampusWebData) {
                return directory;
            }
        }
        throw new IllegalStateException("从 " + start + " 向上未找到仓库根目录");
    }
}
