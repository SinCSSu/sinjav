<template>
  <div class="dir-tree-input">
    <el-input
      v-model="model"
      placeholder="点击选择目录，或直接输入路径"
      @click="open = !open"
    >
      <template v-if="$slots.append" #append>
        <slot name="append" />
      </template>
    </el-input>
    <el-collapse-transition>
      <div v-show="open" class="tree-wrap">
        <el-tree
          :key="treeKey"
          :props="treeProps"
          :load="loadNode"
          lazy
          node-key="path"
          highlight-current
          :expand-on-click-node="false"
          @node-click="onNodeClick"
        />
      </div>
    </el-collapse-transition>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '../api'
import type { FsNode } from '../api/types'

const model = defineModel<string>({ default: '' })
const open = ref(false)

// 每次展开时重建目录树，清掉懒加载缓存
const treeKey = ref(0)
watch(open, (v) => {
  if (v) treeKey.value++
})

const treeProps = {
  label: 'name',
  children: 'children',
  isLeaf: () => false,
}

async function loadNode(node: unknown, resolve: (data: FsNode[]) => void) {
  const n = node as { level: number; data: FsNode }
  try {
    if (n.level === 0) {
      resolve(await api.listFsRoots())
    } else {
      resolve((await api.listFsDirs(n.data.path)).directories)
    }
  } catch {
    resolve([])
  }
}

// 单击=延迟展开下级（等待可能的双击）；双击=仅填入输入框，不展开不收缩
let lastClick = { path: '', time: 0 }
let clickTimer: number | undefined
function onNodeClick(data: FsNode, node: unknown) {
  const n = node as { expanded: boolean; expand: () => void; collapse: () => void }
  const now = Date.now()
  if (lastClick.path === data.path && now - lastClick.time < 400) {
    // 双击：取消待执行的展开，只填路径
    window.clearTimeout(clickTimer)
    model.value = data.path
    lastClick = { path: '', time: 0 }
  } else {
    lastClick = { path: data.path, time: now }
    window.clearTimeout(clickTimer)
    clickTimer = window.setTimeout(() => {
      if (n.expanded) n.collapse()
      else n.expand()
    }, 250)
  }
}
</script>

<style scoped>
/* append 插槽内的按钮撑满整个附加区：左边缘直角与输入框衔接，右侧保留圆角 */
.dir-tree-input :deep(.el-input-group__append) {
  padding: 0;
}
.dir-tree-input :deep(.el-input-group__append .el-button) {
  height: 100%;
  min-height: 100%;
  margin: 0;
  border-radius: 0 4px 4px 0;
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
}
.tree-wrap {
  margin-top: 6px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-small);
  padding: 4px;
  background: var(--el-fill-color-blank);
  max-height: 260px;
  min-height: 120px;
  overflow: auto;
}
</style>
