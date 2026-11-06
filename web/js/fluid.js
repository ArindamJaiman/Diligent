/*
 * Liquid background: a small GPU fluid simulation (stable fluids: advection, vorticity
 * confinement, pressure projection) driven by the pointer. The dye is drawn with a little
 * normal-based shading so it reads as liquid rather than smoke.
 *
 * Diligent.fluid.start(canvas, { palette: () => [[r,g,b], …] }) → controller | null
 * controller.splatAt(clientX, clientY, color?, force?)   ink at a screen point
 * controller.burst(clientX, clientY, color?)             a ring of splats (celebrations)
 * controller.setPaused(bool)
 */
(function () {
  "use strict";
  const D = (window.Diligent = window.Diligent || {});

  const CONFIG = {
    SIM_RESOLUTION: 128,
    DYE_RESOLUTION: 640,
    DENSITY_DISSIPATION: 1.1,
    VELOCITY_DISSIPATION: 0.35,
    PRESSURE: 0.8,
    PRESSURE_ITERATIONS: 18,
    CURL: 22,
    SPLAT_RADIUS: 0.22,
    SPLAT_FORCE: 5200,
    SHADING: true,
  };

  const VERT = `
    precision highp float;
    attribute vec2 aPosition;
    varying vec2 vUv, vL, vR, vT, vB;
    uniform vec2 texelSize;
    void main () {
      vUv = aPosition * 0.5 + 0.5;
      vL = vUv - vec2(texelSize.x, 0.0);
      vR = vUv + vec2(texelSize.x, 0.0);
      vT = vUv + vec2(0.0, texelSize.y);
      vB = vUv - vec2(0.0, texelSize.y);
      gl_Position = vec4(aPosition, 0.0, 1.0);
    }`;

  const HEAD = `precision highp float; precision highp sampler2D;
    varying vec2 vUv, vL, vR, vT, vB;`;

  const FRAG = {
    clear: `${HEAD} uniform sampler2D uTexture; uniform float value;
      void main () { gl_FragColor = value * texture2D(uTexture, vUv); }`,

    splat: `${HEAD} uniform sampler2D uTarget; uniform float aspectRatio; uniform vec3 color; uniform vec2 point; uniform float radius;
      void main () {
        vec2 p = vUv - point.xy; p.x *= aspectRatio;
        vec3 splat = exp(-dot(p, p) / radius) * color;
        gl_FragColor = vec4(texture2D(uTarget, vUv).xyz + splat, 1.0);
      }`,

    advection: `${HEAD} uniform sampler2D uVelocity; uniform sampler2D uSource;
      uniform vec2 texelSize; uniform vec2 dyeTexelSize; uniform float dt; uniform float dissipation;
      vec4 bilerp (sampler2D sam, vec2 uv, vec2 tsize) {
        vec2 st = uv / tsize - 0.5; vec2 iuv = floor(st); vec2 fuv = fract(st);
        vec4 a = texture2D(sam, (iuv + vec2(0.5, 0.5)) * tsize);
        vec4 b = texture2D(sam, (iuv + vec2(1.5, 0.5)) * tsize);
        vec4 c = texture2D(sam, (iuv + vec2(0.5, 1.5)) * tsize);
        vec4 d = texture2D(sam, (iuv + vec2(1.5, 1.5)) * tsize);
        return mix(mix(a, b, fuv.x), mix(c, d, fuv.x), fuv.y);
      }
      void main () {
      #ifdef MANUAL_FILTERING
        vec2 coord = vUv - dt * bilerp(uVelocity, vUv, texelSize).xy * texelSize;
        vec4 result = bilerp(uSource, coord, dyeTexelSize);
      #else
        vec2 coord = vUv - dt * texture2D(uVelocity, vUv).xy * texelSize;
        vec4 result = texture2D(uSource, coord);
      #endif
        gl_FragColor = result / (1.0 + dissipation * dt);
      }`,

    divergence: `${HEAD} uniform sampler2D uVelocity;
      void main () {
        float L = texture2D(uVelocity, vL).x; float R = texture2D(uVelocity, vR).x;
        float T = texture2D(uVelocity, vT).y; float B = texture2D(uVelocity, vB).y;
        vec2 C = texture2D(uVelocity, vUv).xy;
        if (vL.x < 0.0) { L = -C.x; } if (vR.x > 1.0) { R = -C.x; }
        if (vT.y > 1.0) { T = -C.y; } if (vB.y < 0.0) { B = -C.y; }
        gl_FragColor = vec4(0.5 * (R - L + T - B), 0.0, 0.0, 1.0);
      }`,

    curl: `${HEAD} uniform sampler2D uVelocity;
      void main () {
        float L = texture2D(uVelocity, vL).y; float R = texture2D(uVelocity, vR).y;
        float T = texture2D(uVelocity, vT).x; float B = texture2D(uVelocity, vB).x;
        gl_FragColor = vec4(0.5 * (R - L - T + B), 0.0, 0.0, 1.0);
      }`,

    vorticity: `${HEAD} uniform sampler2D uVelocity; uniform sampler2D uCurl; uniform float curl; uniform float dt;
      void main () {
        float L = texture2D(uCurl, vL).x; float R = texture2D(uCurl, vR).x;
        float T = texture2D(uCurl, vT).x; float B = texture2D(uCurl, vB).x;
        float C = texture2D(uCurl, vUv).x;
        vec2 force = 0.5 * vec2(abs(T) - abs(B), abs(R) - abs(L));
        force /= length(force) + 0.0001; force *= curl * C; force.y *= -1.0;
        vec2 velocity = texture2D(uVelocity, vUv).xy + force * dt;
        gl_FragColor = vec4(clamp(velocity, -1000.0, 1000.0), 0.0, 1.0);
      }`,

    pressure: `${HEAD} uniform sampler2D uPressure; uniform sampler2D uDivergence;
      void main () {
        float L = texture2D(uPressure, vL).x; float R = texture2D(uPressure, vR).x;
        float T = texture2D(uPressure, vT).x; float B = texture2D(uPressure, vB).x;
        float divergence = texture2D(uDivergence, vUv).x;
        gl_FragColor = vec4((L + R + B + T - divergence) * 0.25, 0.0, 0.0, 1.0);
      }`,

    gradient: `${HEAD} uniform sampler2D uPressure; uniform sampler2D uVelocity;
      void main () {
        float L = texture2D(uPressure, vL).x; float R = texture2D(uPressure, vR).x;
        float T = texture2D(uPressure, vT).x; float B = texture2D(uPressure, vB).x;
        vec2 velocity = texture2D(uVelocity, vUv).xy - vec2(R - L, T - B);
        gl_FragColor = vec4(velocity, 0.0, 1.0);
      }`,

    display: `${HEAD} uniform sampler2D uTexture; uniform vec2 texelSize; uniform float shading;
      void main () {
        vec3 c = texture2D(uTexture, vUv).rgb;
        if (shading > 0.5) {
          float dx = length(texture2D(uTexture, vR).rgb) - length(texture2D(uTexture, vL).rgb);
          float dy = length(texture2D(uTexture, vT).rgb) - length(texture2D(uTexture, vB).rgb);
          vec3 n = normalize(vec3(dx, dy, length(texelSize)));
          float diffuse = clamp(dot(n, vec3(0.0, 0.0, 1.0)) + 0.7, 0.7, 1.0);
          float spec = pow(clamp(dot(n, normalize(vec3(-0.4, 0.5, 1.0))), 0.0, 1.0), 24.0);
          c = c * diffuse + spec * 0.12 * min(1.0, length(c) * 4.0);
        }
        float a = clamp(max(c.r, max(c.g, c.b)), 0.0, 1.0);
        gl_FragColor = vec4(min(c, vec3(a)), a);
      }`,
  };

  function start(canvas, options) {
    options = options || {};
    const params = { alpha: true, depth: false, stencil: false, antialias: false, preserveDrawingBuffer: false, premultipliedAlpha: true };
    let gl = canvas.getContext("webgl2", params);
    const isWebGL2 = !!gl;
    if (!gl) gl = canvas.getContext("webgl", params) || canvas.getContext("experimental-webgl", params);
    if (!gl) return null;

    let halfFloat, supportLinear;
    if (isWebGL2) {
      gl.getExtension("EXT_color_buffer_float");
      supportLinear = true; // half-float textures filter linearly in WebGL2
    } else {
      halfFloat = gl.getExtension("OES_texture_half_float");
      supportLinear = !!gl.getExtension("OES_texture_half_float_linear");
      if (!halfFloat) return null;
    }
    const texType = isWebGL2 ? gl.HALF_FLOAT : halfFloat.HALF_FLOAT_OES;

    function supportsFormat(internalFormat, format) {
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(gl.TEXTURE_2D, 0, internalFormat, 4, 4, 0, format, texType, null);
      const fbo = gl.createFramebuffer();
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
      const ok = gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;
      gl.deleteFramebuffer(fbo);
      gl.deleteTexture(tex);
      return ok;
    }
    function pickFormat(chain) {
      for (const [i, f] of chain) if (supportsFormat(i, f)) return { internal: i, format: f };
      return null;
    }
    const rgba = isWebGL2 ? pickFormat([[gl.RGBA16F, gl.RGBA]]) : pickFormat([[gl.RGBA, gl.RGBA]]);
    const rg = isWebGL2 ? pickFormat([[gl.RG16F, gl.RG], [gl.RGBA16F, gl.RGBA]]) : rgba;
    const r = isWebGL2 ? pickFormat([[gl.R16F, gl.RED], [gl.RG16F, gl.RG], [gl.RGBA16F, gl.RGBA]]) : rgba;
    if (!rgba || !rg || !r) return null;

    const mobile = /Mobi|Android/i.test(navigator.userAgent);
    if (mobile) CONFIG.DYE_RESOLUTION = 384;
    if (!supportLinear) CONFIG.SHADING = false;

    // ─── Programs ─────────────────────────────────────────
    function compile(type, source, defines) {
      const s = gl.createShader(type);
      gl.shaderSource(s, (defines || []).map((d) => `#define ${d}\n`).join("") + source);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      return s;
    }
    const vertex = compile(gl.VERTEX_SHADER, VERT);
    function program(fragSource, defines) {
      const p = gl.createProgram();
      gl.attachShader(p, vertex);
      gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fragSource, defines));
      gl.bindAttribLocation(p, 0, "aPosition");
      gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
      const uniforms = {};
      const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
      for (let i = 0; i < n; i++) {
        const name = gl.getActiveUniform(p, i).name;
        uniforms[name] = gl.getUniformLocation(p, name);
      }
      return { bind: () => gl.useProgram(p), u: uniforms };
    }

    let P;
    try {
      P = {
        clear: program(FRAG.clear),
        splat: program(FRAG.splat),
        advection: program(FRAG.advection, supportLinear ? [] : ["MANUAL_FILTERING"]),
        divergence: program(FRAG.divergence),
        curl: program(FRAG.curl),
        vorticity: program(FRAG.vorticity),
        pressure: program(FRAG.pressure),
        gradient: program(FRAG.gradient),
        display: program(FRAG.display),
      };
    } catch (e) {
      console.warn("Diligent fluid: shaders failed, using the static background", e);
      return null;
    }

    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, -1, 1, 1, 1, 1, -1]), gl.STATIC_DRAW);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array([0, 1, 2, 0, 2, 3]), gl.STATIC_DRAW);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.enableVertexAttribArray(0);

    function blit(target) {
      if (target == null) {
        gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      } else {
        gl.viewport(0, 0, target.width, target.height);
        gl.bindFramebuffer(gl.FRAMEBUFFER, target.fbo);
      }
      gl.drawElements(gl.TRIANGLES, 6, gl.UNSIGNED_SHORT, 0);
    }

    // ─── Framebuffers ─────────────────────────────────────
    function fbo(w, h, fmt, filter) {
      gl.activeTexture(gl.TEXTURE0);
      const texture = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(gl.TEXTURE_2D, 0, fmt.internal, w, h, 0, fmt.format, texType, null);
      const f = gl.createFramebuffer();
      gl.bindFramebuffer(gl.FRAMEBUFFER, f);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
      gl.viewport(0, 0, w, h);
      gl.clearColor(0, 0, 0, 1);
      gl.clear(gl.COLOR_BUFFER_BIT);
      return {
        texture, fbo: f, width: w, height: h, tx: 1 / w, ty: 1 / h,
        attach(id) { gl.activeTexture(gl.TEXTURE0 + id); gl.bindTexture(gl.TEXTURE_2D, texture); return id; },
      };
    }
    function double(w, h, fmt, filter) {
      let a = fbo(w, h, fmt, filter), b = fbo(w, h, fmt, filter);
      return {
        width: w, height: h, tx: 1 / w, ty: 1 / h,
        get read() { return a; }, get write() { return b; },
        swap() { const t = a; a = b; b = t; },
      };
    }
    function resolution(res) {
      let aspect = gl.drawingBufferWidth / gl.drawingBufferHeight;
      if (aspect < 1) aspect = 1 / aspect;
      const min = Math.round(res), max = Math.round(res * aspect);
      return gl.drawingBufferWidth > gl.drawingBufferHeight ? { w: max, h: min } : { w: min, h: max };
    }

    let dye, velocity, divergence, curl, pressure;
    function initBuffers() {
      const filter = supportLinear ? gl.LINEAR : gl.NEAREST;
      const sim = resolution(CONFIG.SIM_RESOLUTION), d = resolution(CONFIG.DYE_RESOLUTION);
      dye = double(d.w, d.h, rgba, filter);
      velocity = double(sim.w, sim.h, rg, filter);
      divergence = fbo(sim.w, sim.h, r, gl.NEAREST);
      curl = fbo(sim.w, sim.h, r, gl.NEAREST);
      pressure = double(sim.w, sim.h, r, gl.NEAREST);
    }

    function resize() {
      const scale = Math.min(window.devicePixelRatio || 1, 1.5);
      const w = Math.floor(canvas.clientWidth * scale), h = Math.floor(canvas.clientHeight * scale);
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
        return true;
      }
      return false;
    }
    resize();
    initBuffers();

    // ─── Simulation ───────────────────────────────────────
    function step(dt) {
      gl.disable(gl.BLEND);

      P.curl.bind();
      gl.uniform2f(P.curl.u.texelSize, velocity.tx, velocity.ty);
      gl.uniform1i(P.curl.u.uVelocity, velocity.read.attach(0));
      blit(curl);

      P.vorticity.bind();
      gl.uniform2f(P.vorticity.u.texelSize, velocity.tx, velocity.ty);
      gl.uniform1i(P.vorticity.u.uVelocity, velocity.read.attach(0));
      gl.uniform1i(P.vorticity.u.uCurl, curl.attach(1));
      gl.uniform1f(P.vorticity.u.curl, CONFIG.CURL);
      gl.uniform1f(P.vorticity.u.dt, dt);
      blit(velocity.write);
      velocity.swap();

      P.divergence.bind();
      gl.uniform2f(P.divergence.u.texelSize, velocity.tx, velocity.ty);
      gl.uniform1i(P.divergence.u.uVelocity, velocity.read.attach(0));
      blit(divergence);

      P.clear.bind();
      gl.uniform1i(P.clear.u.uTexture, pressure.read.attach(0));
      gl.uniform1f(P.clear.u.value, CONFIG.PRESSURE);
      blit(pressure.write);
      pressure.swap();

      P.pressure.bind();
      gl.uniform2f(P.pressure.u.texelSize, velocity.tx, velocity.ty);
      gl.uniform1i(P.pressure.u.uDivergence, divergence.attach(0));
      for (let i = 0; i < CONFIG.PRESSURE_ITERATIONS; i++) {
        gl.uniform1i(P.pressure.u.uPressure, pressure.read.attach(1));
        blit(pressure.write);
        pressure.swap();
      }

      P.gradient.bind();
      gl.uniform2f(P.gradient.u.texelSize, velocity.tx, velocity.ty);
      gl.uniform1i(P.gradient.u.uPressure, pressure.read.attach(0));
      gl.uniform1i(P.gradient.u.uVelocity, velocity.read.attach(1));
      blit(velocity.write);
      velocity.swap();

      P.advection.bind();
      gl.uniform2f(P.advection.u.texelSize, velocity.tx, velocity.ty);
      if (!supportLinear) gl.uniform2f(P.advection.u.dyeTexelSize, velocity.tx, velocity.ty);
      const vid = velocity.read.attach(0);
      gl.uniform1i(P.advection.u.uVelocity, vid);
      gl.uniform1i(P.advection.u.uSource, vid);
      gl.uniform1f(P.advection.u.dt, dt);
      gl.uniform1f(P.advection.u.dissipation, CONFIG.VELOCITY_DISSIPATION);
      blit(velocity.write);
      velocity.swap();

      if (!supportLinear) gl.uniform2f(P.advection.u.dyeTexelSize, dye.tx, dye.ty);
      gl.uniform1i(P.advection.u.uVelocity, velocity.read.attach(0));
      gl.uniform1i(P.advection.u.uSource, dye.read.attach(1));
      gl.uniform1f(P.advection.u.dissipation, CONFIG.DENSITY_DISSIPATION);
      blit(dye.write);
      dye.swap();
    }

    function render() {
      gl.disable(gl.BLEND);
      P.display.bind();
      gl.uniform2f(P.display.u.texelSize, 1 / gl.drawingBufferWidth, 1 / gl.drawingBufferHeight);
      gl.uniform1i(P.display.u.uTexture, dye.read.attach(0));
      gl.uniform1f(P.display.u.shading, CONFIG.SHADING ? 1 : 0);
      blit(null);
    }

    function aspect() { return canvas.width / canvas.height; }
    function radius(r) { return aspect() > 1 ? r * aspect() : r; }

    function splat(x, y, dx, dy, color, rad) {
      P.splat.bind();
      gl.uniform1i(P.splat.u.uTarget, velocity.read.attach(0));
      gl.uniform1f(P.splat.u.aspectRatio, aspect());
      gl.uniform2f(P.splat.u.point, x, y);
      gl.uniform3f(P.splat.u.color, dx, dy, 0);
      gl.uniform1f(P.splat.u.radius, radius((rad || CONFIG.SPLAT_RADIUS) / 100));
      blit(velocity.write);
      velocity.swap();

      gl.uniform1i(P.splat.u.uTarget, dye.read.attach(0));
      gl.uniform3f(P.splat.u.color, color[0], color[1], color[2]);
      blit(dye.write);
      dye.swap();
    }

    // ─── Colour ───────────────────────────────────────────
    const palette = options.palette || (() => [[1, 1, 1]]);
    let hueStep = 0;
    function nextColor(intensity) {
      const list = palette();
      const c = list[hueStep++ % list.length];
      const k = intensity == null ? 0.16 : intensity;
      return [c[0] * k, c[1] * k, c[2] * k];
    }

    // ─── Pointer ──────────────────────────────────────────
    const pointer = { x: 0.5, y: 0.5, px: 0.5, py: 0.5, dx: 0, dy: 0, moved: false, color: nextColor() };
    let lastColorSwap = 0;
    function onMove(clientX, clientY) {
      const x = clientX / window.innerWidth, y = 1 - clientY / window.innerHeight;
      pointer.px = pointer.x; pointer.py = pointer.y;
      pointer.x = x; pointer.y = y;
      let dx = x - pointer.px, dy = y - pointer.py;
      const a = aspect();
      if (a < 1) dx *= a;
      if (a > 1) dy /= a;
      pointer.dx = dx; pointer.dy = dy;
      pointer.moved = Math.abs(dx) > 0 || Math.abs(dy) > 0;
      const now = performance.now();
      if (now - lastColorSwap > 900) { pointer.color = nextColor(); lastColorSwap = now; }
    }
    let primed = false;
    window.addEventListener("pointermove", (e) => {
      if (!primed) { // the first event only sets the origin, so we don't get a streak from (0.5, 0.5)
        primed = true;
        pointer.x = e.clientX / window.innerWidth; pointer.y = 1 - e.clientY / window.innerHeight;
        return;
      }
      onMove(e.clientX, e.clientY);
    }, { passive: true });
    window.addEventListener("touchmove", (e) => {
      const t = e.touches[0];
      if (t) onMove(t.clientX, t.clientY);
    }, { passive: true });
    window.addEventListener("pointerdown", (e) => {
      if (e.pointerType === "touch") { primed = true; pointer.x = e.clientX / window.innerWidth; pointer.y = 1 - e.clientY / window.innerHeight; }
    }, { passive: true });

    // ─── Loop ─────────────────────────────────────────────
    let paused = false, last = performance.now(), idleAt = performance.now();
    function frame() {
      const now = performance.now();
      const dt = Math.min((now - last) / 1000, 0.016666);
      last = now;
      if (!paused) {
        if (resize()) initBuffers();
        if (pointer.moved) {
          pointer.moved = false;
          splat(pointer.x, pointer.y, pointer.dx * CONFIG.SPLAT_FORCE, pointer.dy * CONFIG.SPLAT_FORCE, pointer.color);
          idleAt = now;
        } else if (now - idleAt > 4200) {
          // Ambient drift so the page stays alive when you're reading.
          idleAt = now;
          const x = 0.15 + Math.random() * 0.7, y = 0.15 + Math.random() * 0.7;
          const ang = Math.random() * Math.PI * 2;
          splat(x, y, Math.cos(ang) * 260, Math.sin(ang) * 260, nextColor(0.08), 0.5);
        }
        step(dt);
        render();
      }
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
    document.addEventListener("visibilitychange", () => { paused = document.hidden; last = performance.now(); });

    return {
      splatAt(clientX, clientY, color, force) {
        const x = clientX / window.innerWidth, y = 1 - clientY / window.innerHeight;
        const ang = Math.random() * Math.PI * 2, f = force == null ? 600 : force;
        splat(x, y, Math.cos(ang) * f, Math.sin(ang) * f, color || nextColor(0.3), 0.35);
      },
      burst(clientX, clientY, color) {
        const x = clientX / window.innerWidth, y = 1 - clientY / window.innerHeight;
        const n = 8;
        for (let i = 0; i < n; i++) {
          const ang = (i / n) * Math.PI * 2;
          const c = color ? color.map((v) => v * 0.35) : nextColor(0.35);
          splat(x, y, Math.cos(ang) * 1400, Math.sin(ang) * 1400, c, 0.18);
        }
      },
      setPaused(p) { paused = !!p; },
    };
  }

  D.fluid = { start };
})();
