export const TILE_WAVE_SPACING = 4;
const MIN_WAVE_INTERVAL_MS = 32;
const MAX_WAVES = 4;
const MIN_MOVEMENT_PX = 1.5;
const WAVE_LEVELS = [0, 0.12, 0.24, 0.42, 0.66];

type WaveTile = { x: number; y: number; strength: number };
type PointerMotion = { dx: number; dy: number; speed: number };
type TileWaveProfile = {
  duration: number;
  amplitude: number;
  spread: number;
  fadeStart: number;
};
type Wave = TileWaveProfile & {
  startedAt: number;
  distances: number[];
  travel: number;
};

export function getTileWaveProfile(speed: number, fullSweep = false): TileWaveProfile {
  return {
    duration: fullSweep ? 1000 : 1050 - speed * 450,
    amplitude: fullSweep ? 1.1 : 0.55 + speed,
    spread: (5 + speed * 6) * 1.2,
    fadeStart: fullSweep ? 0.8 : 0,
  };
}

export class TileWaves {
  private readonly tiles: WaveTile[] = [];
  private readonly waves: Wave[] = [];
  private readonly motionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  private readonly themeObserver: MutationObserver;
  private context: CanvasRenderingContext2D | null = null;
  private frameId: number | undefined;
  private hovering = false;
  private cursor = { x: 0, y: 0 };
  private origin = { x: 0, y: 0, scaleX: 1, scaleY: 1 };
  private lastWaveAt = 0;
  private lastPointerAt = 0;
  private movementDistance = 0;
  private pendingMotion: PointerMotion | undefined;
  private pendingFullSweep = false;
  private animateUntil = 0;
  private color = '';

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly appearance: {
      containsTile?: (x: number, y: number, now: number) => boolean;
      tileIntensity?: (x: number, y: number, now: number) => number;
      isAnimating?: () => boolean;
      baseOpacity?: number;
      baseColor?: string;
      columnPositions?: readonly number[];
    } = {}
  ) {
    const columnCount =
      this.appearance.columnPositions?.length ?? Math.ceil(canvas.width / TILE_WAVE_SPACING);
    for (let row = 0; row < canvas.height / TILE_WAVE_SPACING; row++) {
      for (let column = 0; column < columnCount; column++) {
        const x = this.appearance.columnPositions?.[column] ?? (column + 0.5) * TILE_WAVE_SPACING;
        const y = (row + 0.5) * TILE_WAVE_SPACING;
        this.tiles.push({
          x,
          y,
          strength: 0.35 + (((row * 37 + column * 17 + row * column * 7) % 11) / 10) * 0.65,
        });
      }
    }

    this.themeObserver = new MutationObserver(() => {
      if (this.context && this.frameId !== undefined) this.updateColor();
      if (this.appearance.baseOpacity) this.redraw();
    });
    this.themeObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] });
    this.motionQuery?.addEventListener('change', this.onMotionChange);
    document.addEventListener('visibilitychange', this.onVisibilityChange);
  }

  enter(clientX: number, clientY: number) {
    if (this.motionQuery?.matches || document.hidden) return;
    this.context ??= this.canvas.getContext('2d');
    if (!this.context) return;

    const box = this.canvas.getBoundingClientRect();
    if (!box.width || !box.height) return;
    this.origin = {
      x: box.left,
      y: box.top,
      scaleX: this.canvas.width / box.width,
      scaleY: this.canvas.height / box.height,
    };
    this.cursor = this.cursorPosition(clientX, clientY);
    this.hovering = true;
    this.pendingMotion = undefined;
    this.movementDistance = 0;
    this.updateColor();
    this.lastPointerAt = performance.now();
    this.pendingFullSweep = !this.emit(this.lastPointerAt, { dx: 0, dy: 0, speed: 0.1 }, true);
    this.scheduleFrame();
  }

  move(clientX: number, clientY: number) {
    if (this.motionQuery?.matches || document.hidden) return;
    if (!this.hovering) {
      this.enter(clientX, clientY);
      return;
    }
    const cursor = this.cursorPosition(clientX, clientY);
    const dx = cursor.x - this.cursor.x;
    const dy = cursor.y - this.cursor.y;
    const distance = Math.hypot(dx, dy);
    if (distance < 0.1) return;
    const now = performance.now();
    const elapsed = Math.max(8, Math.min(64, now - this.lastPointerAt));
    const speed = Math.min(1, (distance / elapsed) * 1.4);
    this.cursor = cursor;
    this.lastPointerAt = now;
    this.movementDistance += distance;
    this.pendingMotion = {
      dx,
      dy,
      speed: Math.max(speed, (this.pendingMotion?.speed ?? 0) * 0.7),
    };
    this.scheduleFrame();
  }

  leave() {
    this.hovering = false;
    this.pendingMotion = undefined;
    this.pendingFullSweep = false;
    this.movementDistance = 0;
  }

  redraw(animateUntil = 0) {
    if (document.hidden) return;
    this.context ??= this.canvas.getContext('2d');
    if (!this.context) return;
    this.updateColor();
    this.animateUntil = this.motionQuery?.matches ? 0 : Math.max(this.animateUntil, animateUntil);
    this.scheduleFrame();
  }

  stop() {
    if (this.frameId !== undefined) cancelAnimationFrame(this.frameId);
    this.frameId = undefined;
    this.animateUntil = 0;
    this.leave();
    this.waves.length = 0;
    this.context?.clearRect(0, 0, this.canvas.width, this.canvas.height);
  }

  dispose() {
    this.stop();
    this.themeObserver.disconnect();
    this.motionQuery?.removeEventListener('change', this.onMotionChange);
    document.removeEventListener('visibilitychange', this.onVisibilityChange);
  }

  private readonly onMotionChange = () => {
    if (this.motionQuery?.matches) {
      this.stop();
      if (this.appearance.baseOpacity) this.redraw();
    }
  };

  private readonly onVisibilityChange = () => {
    if (document.hidden) this.stop();
    else if (this.appearance.baseOpacity) this.redraw();
  };

  private updateColor() {
    if (this.context) {
      this.color = getComputedStyle(this.canvas).color;
      this.context.fillStyle = this.color;
    }
  }

  private cursorPosition(clientX: number, clientY: number) {
    return {
      x: (clientX - this.origin.x) * this.origin.scaleX,
      y: (clientY - this.origin.y) * this.origin.scaleY,
    };
  }

  private scheduleFrame() {
    if (this.frameId === undefined) this.frameId = requestAnimationFrame(this.draw);
  }

  private emit(now: number, motion: PointerMotion, fullSweep = false) {
    const profile = getTileWaveProfile(motion.speed, fullSweep);
    if (
      this.waves.length >= MAX_WAVES ||
      (!fullSweep &&
        now - this.lastWaveAt < Math.max(MIN_WAVE_INTERVAL_MS, profile.duration / MAX_WAVES))
    )
      return false;
    // Cache direction-stretched distances once per wave, keeping frame work geometry-free.
    const length = Math.hypot(motion.dx, motion.dy);
    const directionX = length > 0 ? motion.dx / length : 1;
    const directionY = length > 0 ? motion.dy / length : 0;
    const distances = this.tiles.map((tile) => {
      const dx = tile.x - this.cursor.x;
      const dy = tile.y - this.cursor.y;
      const along = dx * directionX + dy * directionY;
      const across = dy * directionX - dx * directionY;
      return Math.hypot(along / (fullSweep ? 1 : 1 + motion.speed * 0.6), across);
    });
    this.waves.push({
      startedAt: now,
      ...profile,
      // Keep the entry wave bright until it has passed even the farthest tile.
      travel: fullSweep
        ? (distances.reduce((farthest, distance) => Math.max(farthest, distance), 0) +
            profile.spread) *
          1.25
        : Math.min(this.canvas.width, this.canvas.height) * (0.65 + motion.speed * 0.65),
      distances,
    });
    this.lastWaveAt = now;
    return true;
  }

  private readonly draw = (now: number) => {
    const context = this.context;
    if (!context) return;
    for (let index = this.waves.length - 1; index >= 0; index--) {
      const wave = this.waves[index]!;
      if (now - wave.startedAt >= wave.duration) this.waves.splice(index, 1);
    }
    // Coalesce pointer events into at most one new wave per frame. Still cursors emit nothing.
    if (this.pendingFullSweep) {
      if (this.emit(now, this.pendingMotion ?? { dx: 0, dy: 0, speed: 0.1 }, true)) {
        this.pendingFullSweep = false;
        this.pendingMotion = undefined;
        this.movementDistance = 0;
      }
    } else if (
      this.pendingMotion &&
      this.movementDistance >= MIN_MOVEMENT_PX &&
      now - this.lastWaveAt >= MIN_WAVE_INTERVAL_MS &&
      this.emit(now, this.pendingMotion)
    ) {
      this.pendingMotion = undefined;
      this.movementDistance = 0;
    }
    context.clearRect(0, 0, this.canvas.width, this.canvas.height);
    const isAnimating = !this.motionQuery?.matches && (this.appearance.isAnimating?.() ?? false);

    if (
      this.waves.length === 0 &&
      !this.appearance.baseOpacity &&
      !isAnimating &&
      now >= this.animateUntil
    ) {
      this.frameId = undefined;
      return;
    }

    let fillColor = this.color;
    context.fillStyle = fillColor;
    for (let index = 0; index < this.tiles.length; index++) {
      const tile = this.tiles[index]!;
      if (this.appearance.containsTile && !this.appearance.containsTile(tile.x, tile.y, now))
        continue;
      let intensity = (this.appearance.tileIntensity?.(tile.x, tile.y, now) ?? 0) * tile.strength;
      for (const wave of this.waves) {
        const age = (now - wave.startedAt) / wave.duration;
        const ring = Math.max(
          0,
          1 - Math.abs(wave.distances[index]! - age * wave.travel) / wave.spread
        );
        const fade = Math.min(1, (1 - age) / (1 - wave.fadeStart));
        intensity = Math.max(intensity, ring * fade * tile.strength * wave.amplitude);
      }
      const level = Math.min(4, Math.floor(intensity * 5));
      const baseAlpha = (this.appearance.baseOpacity ?? 0) * tile.strength;
      if (level === 0 && baseAlpha === 0) continue;
      const size = (1.5 + Math.max(1, level) * 0.35) * 1.2;
      const color = level === 0 ? (this.appearance.baseColor ?? this.color) : this.color;
      if (fillColor !== color) {
        context.fillStyle = color;
        fillColor = color;
      }
      context.globalAlpha = level === 0 ? baseAlpha : WAVE_LEVELS[level]!;
      context.fillRect(tile.x - size / 2, tile.y - size / 2, size, size);
    }
    this.frameId =
      this.waves.length > 0 || now < this.animateUntil || isAnimating
        ? requestAnimationFrame(this.draw)
        : undefined;
  };
}
