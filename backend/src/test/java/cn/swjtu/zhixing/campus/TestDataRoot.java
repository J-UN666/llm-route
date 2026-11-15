package cn.swjtu.zhixing.campus;

import java.nio.file.Files;
import java.nio.file.Path;

/** 测试用仓库根目录定位：从测试工作目录（backend/）向上找到含校园数据与 web/data/campus.json 的目录。 */
final class TestDataRoot {

    private TestDataRoot() {
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
