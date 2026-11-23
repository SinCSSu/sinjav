<template>
  <Teleport to="body">
    <el-image-viewer
      v-if="visible"
      :url-list="urls"
      :initial-index="initialIndex"
      :infinite="false"
      hide-on-click-modal
      @switch="onSwitch"
      @close="emit('close')"
    />
    <div v-if="visible" class="img-viewer-counter">{{ current + 1 }} / {{ urls.length }}</div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{
  visible: boolean
  urls: string[]
  initialIndex?: number
}>()
const emit = defineEmits<{ close: [] }>()

const current = ref(props.initialIndex ?? 0)

watch(
  () => props.visible,
  (v) => {
    if (v) current.value = props.initialIndex ?? 0
  },
)

function onSwitch(idx: number) {
  current.value = idx
}
</script>

<style scoped>
.img-viewer-counter {
  position: fixed;
  top: 22px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 4000;
  padding: 4px 14px;
  border-radius: var(--el-border-radius-round);
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 13px;
  pointer-events: none;
  user-select: none;
}
</style>
