"""Compile the real renderer before/after against small Minecraft boundary stubs.
Hidden GLFW context; actual driver, shaders, buffers and framebuffer operations.
No game/account files touched. CPU/GPU microbenchmarks are not game acceptance.
"""
from pathlib import Path
import subprocess, os, json
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java',required=True,help='JDK 21 java executable')
parser.add_argument('--gradle-lib',type=Path,required=True,help='Gradle lib directory with Kotlin compiler and stdlib 2.2.21')
parser.add_argument('--cache',type=Path,required=True,help='Gradle caches/modules-2/files-2.1')
parser.add_argument('--work',type=Path)
parser.add_argument('--before',default='7c37413',help='Git revision to compare against')
parser.add_argument('--after-only',action='store_true')
args=parser.parse_args()
repo=Path(__file__).resolve().parents[2]
bench=args.work or repo/'.gradle/render-regression'
bench.mkdir(parents=True,exist_ok=True)
cache=args.cache;gradle=args.gradle_lib;java=Path(args.java)
deps=[]
for module in ['lwjgl','lwjgl-glfw','lwjgl-opengl']:
    for file in (cache/'org.lwjgl'/module/'3.3.3').rglob('*.jar'):
        if file.name in [module+'-3.3.3.jar', module+'-3.3.3-natives-windows.jar']: deps.append(file)
deps += list((cache/'org.joml/joml/1.10.5').rglob('joml-1.10.5.jar'))
deps += [gradle/'kotlin-stdlib-2.2.21.jar',gradle/'annotations-24.0.1.jar']
assert len(deps)==9, [str(x) for x in deps]
cp=';'.join(map(str,deps))
resources=bench/'resources/assets/dangomusic/shaders'; resources.mkdir(parents=True,exist_ok=True)
(resources/'uniform-test.vert').write_text('''#version 150
in vec2 a_Pos;
uniform mat4 matrix;
uniform vec2 offset;
void main(){ gl_Position=matrix*vec4(a_Pos+offset,0.0,1.0); }
''',encoding='utf-8')
(resources/'uniform-test.frag').write_text('''#version 150
out vec4 fragColor;
uniform vec4 color;
uniform float scalar;
uniform int mode;
void main(){ fragColor=color*scalar*float(mode); }
''',encoding='utf-8')
stubs={
'Minecraft.kt': '''package net.minecraft.client
class Window { var width=2560; var height=1600 }
class Minecraft { val window=Window(); companion object { private val instance=Minecraft(); @JvmStatic fun getInstance()=instance } }
''',
'Color.kt': '''package me.sorihiko.dango.utils
class Color(val r:Float,val g:Float,val b:Float,val a:Float)
''',
'Compat.kt': '''package me.sorihiko.dango.modern
import org.lwjgl.opengl.*
import org.joml.Matrix4f
object McCompat {
 fun beforeDraw() {}
 fun projection()=Matrix4f()
 fun modelView()=Matrix4f()
 fun bindFramebuffer(target:Int,id:Int)=GL30.glBindFramebuffer(target,id)
}
object GlStateManager {
 fun _blendFuncSeparate(a:Int,b:Int,c:Int,d:Int)=GL14.glBlendFuncSeparate(a,b,c,d)
 fun _enableDepthTest()=GL11.glEnable(GL11.GL_DEPTH_TEST)
 fun _disableDepthTest()=GL11.glDisable(GL11.GL_DEPTH_TEST)
 fun _depthMask(v:Boolean)=GL11.glDepthMask(v)
 fun _enableCull()=GL11.glEnable(GL11.GL_CULL_FACE)
 fun _disableCull()=GL11.glDisable(GL11.GL_CULL_FACE)
 fun _enableScissorTest()=GL11.glEnable(GL11.GL_SCISSOR_TEST)
 fun _disableScissorTest()=GL11.glDisable(GL11.GL_SCISSOR_TEST)
 fun _scissorBox(a:Int,b:Int,c:Int,d:Int)=GL11.glScissor(a,b,c,d)
 fun _viewport(a:Int,b:Int,c:Int,d:Int)=GL11.glViewport(a,b,c,d)
 fun _activeTexture(t:Int)=GL13.glActiveTexture(t)
 fun _bindTexture(t:Int)=GL11.glBindTexture(GL11.GL_TEXTURE_2D,t)
 fun _deleteTexture(t:Int)=GL11.glDeleteTextures(t)
}
object GlCache {
 fun blend(v:Boolean) { if(v)GL11.glEnable(GL11.GL_BLEND)else GL11.glDisable(GL11.GL_BLEND) }
 fun colorMask(a:Boolean,b:Boolean,c:Boolean,d:Boolean)=GL11.glColorMask(a,b,c,d)
}
class Shaders {
 private val programs=mutableMapOf<Class<*>,me.sorihiko.dango.shader.GlShader>()
 fun <T:me.sorihiko.dango.shader.GlShader> get(c:Class<T>):T = c.cast(programs.getOrPut(c){ c.getDeclaredConstructor().newInstance().also{it.linkProgram()} })
 inline fun <reified T:me.sorihiko.dango.shader.GlShader> get():T=get(T::class.java)
}
object ModernGl { val shaderManager=Shaders() }
'''
}
for name,body in stubs.items(): (bench/name).write_text(body,encoding='utf-8')
files=['modern/Imm.kt','modern/Gl.kt','shader/GlShader.kt','shader/impls/QuadShader.kt','shader/impls/ui/GlassBlurShader.kt','ui/glass/GlassBackdrop.kt']
base='modern/src/main/kotlin/me/sorihiko/dango/'
for variant in (['after'] if args.after_only else ['before','after']):
    target=bench/variant; target.mkdir(exist_ok=True)
    sources=[]
    for path in files:
        src=target/Path(path).name
        data=subprocess.check_output(['git','show',args.before+':'+base+path],cwd=repo) if variant=='before' else (repo/base/path).read_bytes()
        src.write_bytes(data); sources.append(src)
    dest=target/'classes'; dest.mkdir(exist_ok=True)
    args=[java,'-Xmx512m','-cp',str(gradle/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','21','-no-reflect','-no-stdlib','-cp',cp,'-d',dest,*sources,*[bench/x for x in stubs],Path(__file__).with_name('RenderBenchmark.kt')]
    result=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',errors='replace')
    (target/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8')
    if result.returncode: raise RuntimeError(variant+': '+result.stderr)
    result=subprocess.run([str(java),'-Xmx256m','-XX:ActiveProcessorCount=2','-cp',str(dest)+';'+cp+';'+str(repo/'modern/src/main/resources')+';'+str(bench/'resources'),'RenderBenchmarkKt',variant],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
    (target/'result.log').write_text(result.stdout+result.stderr,encoding='utf-8')
    print(result.stdout,flush=True)
    if result.returncode: raise RuntimeError(variant+': '+result.stderr)
