import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js';
import { OBJLoader } from 'three/examples/jsm/loaders/OBJLoader.js';
import { PLYLoader } from 'three/examples/jsm/loaders/PLYLoader.js';

export type PerspectivePreset = 'front' | 'top' | 'bottom' | 'left' | 'right' | 'isometric' | 'reset';

export interface Dental3DOptions {
  canvas: HTMLCanvasElement;
  container: HTMLElement;
  initialColor?: number;
  initialWireframe?: boolean;
  initialAutoRotate?: boolean;
}

export function is3DFile(fileName?: string | null): boolean {
  if (!fileName) return false;
  const lower = fileName.toLowerCase().trim();
  return (
    lower.endsWith('.stl') ||
    lower.endsWith('.obj') ||
    lower.endsWith('.ply') ||
    lower.endsWith('.gltf') ||
    lower.endsWith('.glb')
  );
}

export class Dental3DScene {
  private canvas: HTMLCanvasElement;
  private container: HTMLElement;
  private renderer!: THREE.WebGLRenderer;
  private scene!: THREE.Scene;
  private camera!: THREE.PerspectiveCamera;
  private controls!: OrbitControls;
  private animFrameId: number | null = null;
  private currentMesh: THREE.Object3D | null = null;
  private material!: THREE.MeshStandardMaterial;

  private isAutoRotating = false;
  private isWireframe = false;
  private modelRadius = 50;

  constructor(options: Dental3DOptions) {
    this.canvas = options.canvas;
    this.container = options.container;
    this.isWireframe = options.initialWireframe ?? false;
    this.isAutoRotating = options.initialAutoRotate ?? false;

    this.initScene(options.initialColor ?? 0xf1f5f9);
    this.animate();
  }

  private initScene(color: number): void {
    const width = Math.max(100, this.container.clientWidth || this.canvas.clientWidth || 800);
    const height = Math.max(100, this.container.clientHeight || this.canvas.clientHeight || 500);

    // 1. Scene & Background
    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x060913); // deep medical dark slate

    // 2. Camera
    this.camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 2000);
    this.camera.position.set(0, 40, 100);

    // 3. Renderer
    this.renderer = new THREE.WebGLRenderer({
      canvas: this.canvas,
      antialias: true,
      alpha: false,
      powerPreference: 'high-performance',
    });
    this.renderer.setSize(width, height);
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.1;

    // 4. Studio Lighting for Dental Anatomy
    // Soft Ambient
    const ambientLight = new THREE.AmbientLight(0xffffff, 0.9);
    this.scene.add(ambientLight);

    // Key directional light (top-front-right)
    const keyLight = new THREE.DirectionalLight(0xffffff, 1.4);
    keyLight.position.set(60, 100, 80);
    this.scene.add(keyLight);

    // Fill light (front-left)
    const fillLight = new THREE.DirectionalLight(0x93c5fd, 0.9);
    fillLight.position.set(-60, 50, 60);
    this.scene.add(fillLight);

    // Rim light (under/back for occlusal depth)
    const rimLight = new THREE.DirectionalLight(0x38bdf8, 0.6);
    rimLight.position.set(0, -60, -60);
    this.scene.add(rimLight);

    // Subtle grid helper on the ground
    const grid = new THREE.GridHelper(120, 24, 0x1e293b, 0x0f172a);
    grid.position.y = -25;
    this.scene.add(grid);

    // 5. Default Material (Realistic Dental Ceramic/Enamel)
    this.material = new THREE.MeshStandardMaterial({
      color: new THREE.Color(color),
      roughness: 0.3,
      metalness: 0.05,
      wireframe: this.isWireframe,
      side: THREE.DoubleSide,
    });

    // 6. Orbit Controls
    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.enablePan = true;
    this.controls.enableZoom = true;
    this.controls.autoRotate = this.isAutoRotating;
    this.controls.autoRotateSpeed = 2.0;
  }

  public async loadModel(data: ArrayBuffer | string, fileName: string): Promise<void> {
    const ext = fileName.toLowerCase().split('.').pop() || '';

    let object3D: THREE.Object3D | null = null;

    if (ext === 'stl') {
      const loader = new STLLoader();
      const geom = typeof data === 'string' ? loader.parse(new TextEncoder().encode(data).buffer) : loader.parse(data);
      geom.computeVertexNormals();
      object3D = new THREE.Mesh(geom, this.material);
    } else if (ext === 'ply') {
      const loader = new PLYLoader();
      const geom = typeof data === 'string' ? loader.parse(new TextEncoder().encode(data).buffer) : loader.parse(data);
      geom.computeVertexNormals();
      object3D = new THREE.Mesh(geom, this.material);
    } else if (ext === 'obj') {
      const loader = new OBJLoader();
      const text = typeof data === 'string' ? data : new TextDecoder().decode(data);
      const parsedGroup = loader.parse(text);
      parsedGroup.traverse((child) => {
        if ((child as THREE.Mesh).isMesh) {
          (child as THREE.Mesh).material = this.material;
        }
      });
      object3D = parsedGroup;
    } else {
      throw new Error(`Unsupported 3D format: .${ext}`);
    }

    if (this.currentMesh) {
      this.scene.remove(this.currentMesh);
      this.disposeObject(this.currentMesh);
      this.currentMesh = null;
    }

    this.currentMesh = object3D;
    this.scene.add(this.currentMesh);

    // Center and auto-scale model into view
    this.centerAndFrameModel(this.currentMesh);
  }

  private centerAndFrameModel(obj: THREE.Object3D): void {
    const box = new THREE.Box3().setFromObject(obj);
    const center = new THREE.Vector3();
    box.getCenter(center);
    obj.position.sub(center); // re-center at (0, 0, 0)

    const sphere = new THREE.Sphere();
    box.getBoundingSphere(sphere);
    this.modelRadius = Math.max(10, sphere.radius);

    this.controls.target.set(0, 0, 0);
    this.setPerspective('reset');
  }

  public setPerspective(preset: PerspectivePreset): void {
    if (!this.camera || !this.controls) return;
    const r = this.modelRadius * 2.3;

    switch (preset) {
      case 'front': // Anterior / Facial
        this.camera.position.set(0, 0, r);
        this.camera.up.set(0, 1, 0);
        break;
      case 'top': // Occlusal View
        this.camera.position.set(0, r, 0.001);
        this.camera.up.set(0, 0, -1);
        break;
      case 'bottom': // Lingual / Basal View
        this.camera.position.set(0, -r, 0.001);
        this.camera.up.set(0, 0, 1);
        break;
      case 'left': // Left Lateral
        this.camera.position.set(-r, 0, 0);
        this.camera.up.set(0, 1, 0);
        break;
      case 'right': // Right Lateral
        this.camera.position.set(r, 0, 0);
        this.camera.up.set(0, 1, 0);
        break;
      case 'isometric':
      case 'reset':
      default:
        this.camera.position.set(r * 0.7, r * 0.6, r * 0.8);
        this.camera.up.set(0, 1, 0);
        break;
    }

    this.controls.target.set(0, 0, 0);
    this.controls.update();
  }

  public toggleWireframe(): boolean {
    this.isWireframe = !this.isWireframe;
    this.material.wireframe = this.isWireframe;
    this.material.needsUpdate = true;
    return this.isWireframe;
  }

  public getWireframe(): boolean {
    return this.isWireframe;
  }

  public toggleAutoRotate(): boolean {
    this.isAutoRotating = !this.isAutoRotating;
    this.controls.autoRotate = this.isAutoRotating;
    return this.isAutoRotating;
  }

  public getAutoRotate(): boolean {
    return this.isAutoRotating;
  }

  public setMaterialColor(color: number | string): void {
    this.material.color.set(color);
    this.material.needsUpdate = true;
  }

  public zoom(delta: number): void {
    const factor = delta > 0 ? 0.85 : 1.15;
    this.camera.position.multiplyScalar(factor);
    this.controls.update();
  }

  public resize(): void {
    if (!this.renderer || !this.camera || !this.container) return;
    const width = Math.max(100, this.container.clientWidth || 800);
    const height = Math.max(100, this.container.clientHeight || 500);

    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(width, height);
  }

  private animate = (): void => {
    this.animFrameId = requestAnimationFrame(this.animate);
    this.controls.update();
    this.renderer.render(this.scene, this.camera);
  };

  private disposeObject(obj: THREE.Object3D): void {
    obj.traverse((child) => {
      if ((child as THREE.Mesh).isMesh) {
        const mesh = child as THREE.Mesh;
        mesh.geometry?.dispose();
        if (Array.isArray(mesh.material)) {
          mesh.material.forEach((m) => m.dispose());
        } else {
          mesh.material?.dispose();
        }
      }
    });
  }

  public dispose(): void {
    if (this.animFrameId !== null) {
      cancelAnimationFrame(this.animFrameId);
      this.animFrameId = null;
    }
    this.controls?.dispose();
    if (this.currentMesh) {
      this.disposeObject(this.currentMesh);
      this.currentMesh = null;
    }
    this.material?.dispose();
    this.renderer?.dispose();
  }
}
