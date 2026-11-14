<script setup lang="ts">
import { onLaunch, onShow, onHide } from "@dcloudio/uni-app";

onLaunch(() => {
  console.log("[交大智行] App Launch");
});
onShow(() => {
  console.log("[交大智行] App Show");
});
onHide(() => {
  console.log("[交大智行] App Hide");
});
</script>

<style>
page {
  background-color: #f5f7fa;
  color: #131a24;
  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", "PingFang SC",
    "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  -webkit-font-smoothing: antialiased;
}
</style>
