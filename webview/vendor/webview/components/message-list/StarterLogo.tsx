import { createSignal, onCleanup, onMount } from 'solid-js';
import chatLogoUri from '../../../../assets/icon.png?inline';
import { toCssUrl } from '../UiIcon';

const WAVE_SIZE = 64;
const TILE_SPACING = 4;
const MIN_WAVE_INTERVAL_MS = 32;
const MIN_MOVEMENT_PX = 1.5;
const WAVE_LEVELS = [0, 0.12, 0.24, 0.42, 0.66];

type WaveTile = { x: number; y: number; strength: number };
type PointerMotion = { dx: number; dy: number; speed: number };
type Wave = {
  startedAt: number;
  distances: number[];
  duration: number;
  amplitude: number;
  spread: number;
  travel: number;
  fadeStart: number;
};

class LogoWaves {
  private readonly tiles: WaveTile[] = [];
  private readonly waves: Wave[] = [];
  private readonly motionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  private readonly themeObserver: MutationObserver;
  private context: CanvasRenderingContext2D | null = null;
  private frameId: number | undefined;
  private hovering = false;
  private cursor = { x: WAVE_SIZE / 2, y: WAVE_SIZE / 2 };
  private origin = { x: 0, y: 0, scaleX: 1, scaleY: 1 };
  private lastWaveAt = 0;
  private lastPointerAt = 0;
  private movementDistance = 0;
  private pendingMotion: PointerMotion | undefined;

  constructor(private readonly canvas: HTMLCanvasElement) {
    for (let row = 0; row < WAVE_SIZE / TILE_SPACING; row++) {
      for (let column = 0; column < WAVE_SIZE / TILE_SPACING; column++) {
        const x = (column + 0.5) * TILE_SPACING;
        const y = (row + 0.5) * TILE_SPACING;
        this.tiles.push({
          x,
          y,
          strength: 0.35 + (((row * 37 + column * 17 + row * column * 7) % 11) / 10) * 0.65,
        });
      }
    }

    this.themeObserver = new MutationObserver(() => {
      if (this.context && this.frameId !== undefined) this.updateColor();
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
    this.origin = {
      x: box.left,
      y: box.top,
      scaleX: WAVE_SIZE / box.width,
      scaleY: WAVE_SIZE / box.height,
    };
    this.cursor = this.cursorPosition(clientX, clientY);
    this.hovering = true;
    this.pendingMotion = undefined;
    this.movementDistance = 0;
    this.updateColor();
    this.lastPointerAt = performance.now();
    this.emit(this.lastPointerAt, { dx: 0, dy: 0, speed: 0.1 }, 'logo');
    this.scheduleFrame();
  }

  move(clientX: number, clientY: number) {
    if (!this.hovering || this.motionQuery?.matches || document.hidden) return;
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
    this.movementDistance = 0;
  }

  stop() {
    if (this.frameId !== undefined) cancelAnimationFrame(this.frameId);
    this.frameId = undefined;
    this.leave();
    this.waves.length = 0;
    this.context?.clearRect(0, 0, WAVE_SIZE, WAVE_SIZE);
  }

  dispose() {
    this.stop();
    this.themeObserver.disconnect();
    this.motionQuery?.removeEventListener('change', this.onMotionChange);
    document.removeEventListener('visibilitychange', this.onVisibilityChange);
  }

  private readonly onMotionChange = () => {
    if (this.motionQuery?.matches) this.stop();
  };

  private readonly onVisibilityChange = () => {
    if (document.hidden) this.stop();
  };

  private updateColor() {
    if (this.context) this.context.fillStyle = getComputedStyle(this.canvas).color;
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

  private emit(now: number, motion: PointerMotion, coverage: 'local' | 'logo' = 'local') {
    // Cache direction-stretched distances once per wave, keeping frame work geometry-free.
    const length = Math.hypot(motion.dx, motion.dy);
    const directionX = length > 0 ? motion.dx / length : 1;
    const directionY = length > 0 ? motion.dy / length : 0;
    const fullSweep = coverage === 'logo';
    const spread = (5 + motion.speed * 6) * 1.2;
    const distances = this.tiles.map((tile) => {
      const dx = tile.x - this.cursor.x;
      const dy = tile.y - this.cursor.y;
      const along = dx * directionX + dy * directionY;
      const across = dy * directionX - dx * directionY;
      return Math.hypot(along / (fullSweep ? 1 : 1 + motion.speed * 0.6), across);
    });
    if (this.waves.length >= 4) this.waves.shift();
    this.waves.push({
      startedAt: now,
      duration: fullSweep ? 1000 : 1050 - motion.speed * 450,
      amplitude: fullSweep ? 1.1 : 0.55 + motion.speed,
      spread,
      // Keep the entry wave bright until it has passed even the farthest logo tile.
      travel: fullSweep
        ? (Math.max(...distances) + spread) * 1.25
        : WAVE_SIZE * (0.65 + motion.speed * 0.65),
      fadeStart: fullSweep ? 0.8 : 0,
      distances,
    });
    this.lastWaveAt = now;
  }

  private readonly draw = (now: number) => {
    const context = this.context;
    if (!context) return;
    // Coalesce pointer events into at most one new wave per frame. Still cursors emit nothing.
    if (
      this.pendingMotion &&
      this.movementDistance >= MIN_MOVEMENT_PX &&
      now - this.lastWaveAt >= MIN_WAVE_INTERVAL_MS
    ) {
      this.emit(now, this.pendingMotion);
      this.pendingMotion = undefined;
      this.movementDistance = 0;
    }
    for (let index = this.waves.length - 1; index >= 0; index--) {
      const wave = this.waves[index]!;
      if (now - wave.startedAt >= wave.duration) this.waves.splice(index, 1);
    }
    context.clearRect(0, 0, WAVE_SIZE, WAVE_SIZE);

    if (this.waves.length === 0) {
      this.frameId = undefined;
      return;
    }

    for (let index = 0; index < this.tiles.length; index++) {
      const tile = this.tiles[index]!;
      let intensity = 0;
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
      if (level === 0) continue;
      const size = (1.5 + level * 0.35) * 1.2;
      context.globalAlpha = WAVE_LEVELS[level]!;
      context.fillRect(tile.x - size / 2, tile.y - size / 2, size, size);
    }
    this.frameId = requestAnimationFrame(this.draw);
  };
}

export function StarterLogo() {
  // oxlint-disable-next-line no-unassigned-vars
  let logoRef: HTMLImageElement | undefined;
  // oxlint-disable-next-line no-unassigned-vars
  let canvasRef: HTMLCanvasElement | undefined;
  let waves: LogoWaves | undefined;
  const [position, setPosition] = createSignal<{ top: string; left: string }>();

  onMount(() => {
    const logo = logoRef;
    const area = logo?.closest<HTMLElement>('.interactive-list-shell');
    const hints = logo
      ?.closest('.chat-empty-state')
      ?.querySelector<HTMLElement>('.chat-empty-hints');
    if (!logo || !area || !hints || !canvasRef) return;
    waves = new LogoWaves(canvasRef);

    const updateOverlap = () => {
      const logoBox = logo.getBoundingClientRect();
      const hintsBox = hints.getBoundingClientRect();
      const overlaps =
        logoBox.left < hintsBox.right &&
        logoBox.right > hintsBox.left &&
        logoBox.top < hintsBox.bottom &&
        logoBox.bottom > hintsBox.top;
      hints.classList.toggle('is-logo-overlapping', overlaps);
    };

    const updatePosition = () => {
      waves?.stop();
      const box = area.getBoundingClientRect();
      setPosition({
        top: `${box.top + box.height / 2}px`,
        left: `${box.left + box.width / 2}px`,
      });
      updateOverlap();
    };

    // Capture the initial message area, not its later composer-driven resizing.
    updatePosition();
    const observer = new ResizeObserver(updateOverlap);
    observer.observe(area);
    observer.observe(logo);
    observer.observe(hints);
    window.addEventListener('resize', updatePosition);
    onCleanup(() => {
      waves?.dispose();
      observer.disconnect();
      window.removeEventListener('resize', updatePosition);
      hints.classList.remove('is-logo-overlapping');
    });
  });

  return (
    <span class="chat-empty-logo-shell" style={position()} aria-hidden="true">
      <img
        ref={logoRef}
        class="chat-empty-logo"
        src={chatLogoUri}
        decoding="sync"
        width="256"
        height="256"
        alt=""
        aria-hidden="true"
        draggable="false"
        onPointerEnter={(event) => waves?.enter(event.clientX, event.clientY)}
        onPointerMove={(event) => waves?.move(event.clientX, event.clientY)}
        onPointerLeave={() => waves?.leave()}
        onPointerCancel={() => waves?.leave()}
      />
      <canvas
        ref={canvasRef}
        class="chat-empty-logo-waves"
        style={{ 'mask-image': toCssUrl(chatLogoUri) }}
        width={WAVE_SIZE}
        height={WAVE_SIZE}
        aria-hidden="true"
      />
    </span>
  );
}
