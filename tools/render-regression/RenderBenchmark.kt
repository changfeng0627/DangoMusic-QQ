import me.sorihiko.dango.modern.*
import me.sorihiko.dango.shader.impls.QuadShader
import me.sorihiko.dango.ui.glass.GlassBackdrop
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.*
import org.lwjgl.BufferUtils
import kotlin.math.abs

class UniformProbe:me.sorihiko.dango.shader.GlShader("uniform-test.vert","uniform-test.frag") {
 fun submit(value:Float) {
     setUniform1f("scalar",value);setUniform1i("mode",2)
     setUniform2f("offset",value,value+1f);setUniform4f("color",value,value+1f,value+2f,value+3f)
     val matrix=BufferUtils.createFloatBuffer(16);org.joml.Matrix4f().translation(value,0f,0f).get(matrix)
     setUniformMat4("matrix",matrix);check(matrix.position()==0)
     // Optimized-away uniforms all have location -1, even with different types.
     setUniform1f("missing",value);setUniform4f("missing4",value,value,value,value)
     setUniform2f("missing2",value,value);setUniformMat4("missingMatrix",matrix)
 }
}

fun main(args:Array<String>) {
 check(glfwInit())
 glfwDefaultWindowHints()
 glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
 glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3)
 glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE)
 val win=glfwCreateWindow(64,64,"Dango isolated renderer test",0,0)
 check(win!=0L)
 glfwMakeContextCurrent(win); GL.createCapabilities()
 try {
 println("variant=${args[0]} renderer=${GL11.glGetString(GL11.GL_RENDERER)}")
 val uniformProbe=UniformProbe();uniformProbe.linkProgram();uniformProbe.bind()
 for(v in listOf(1f,1f,3f,3f,2f)) {
     uniformProbe.submit(v)
     GL20.glUseProgram(0);uniformProbe.bind();uniformProbe.submit(v)
     val p=uniformProbe.getProgramID()
     check(GL20.glGetUniformf(p,GL20.glGetUniformLocation(p,"scalar"))==v)
     check(GL20.glGetUniformi(p,GL20.glGetUniformLocation(p,"mode"))==2)
     val vec=FloatArray(4); GL20.glGetUniformfv(p,GL20.glGetUniformLocation(p,"color"),vec)
     check(vec.contentEquals(floatArrayOf(v,v+1,v+2,v+3)))
     val matrix=FloatArray(16);GL20.glGetUniformfv(p,GL20.glGetUniformLocation(p,"matrix"),matrix)
     check(matrix[12]==v)
 }
 uniformProbe.unbind();check(GL11.glGetError()==GL11.GL_NO_ERROR)
 println("uniform-repeat/change/matrix/missing PASS")
 val source=GL30.glGenFramebuffers()
 val tex=GL11.glGenTextures()
 GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex)
 GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,2560,1600,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,0L)
 GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR)
 GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR)
 GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,source)
 GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0)
 check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE)
 GL11.glViewport(0,0,2560,1600)
 GL11.glClearColor(0.2f,0.4f,0.6f,1f); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
 val b=GlassBackdrop()
 val next=GlassBackdrop::class.java.methods.firstOrNull{it.name=="nextFrame"}
 val companion=GlassBackdrop::class.java.fields.firstOrNull{it.name=="Companion"}?.get(null)
 val frameMethod=companion?.javaClass?.methods?.firstOrNull{it.name=="nextFrame"}
 val time=GlassBackdrop::class.java.declaredFields.firstOrNull{it.name=="capturedAt"}?.apply{isAccessible=true}
 fun capture() {
     if(time!=null)time.setLong(b,0L)
     if(next!=null)next.invoke(null) else frameMethod?.invoke(companion)
     b.capture()
 }
 fun state()=intArrayOf(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),if(GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB))1 else 0)
 fun validate(label:String) {
     val before=state(); capture(); check(before.contentEquals(state())){"state restore $label"}
     check(GL11.glGetError()==GL11.GL_NO_ERROR){"GL error $label"}
     val read=GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read)
     GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,b.softTexture,0)
     val px=BufferUtils.createByteBuffer(4)
     GL11.glReadPixels(2,2,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,px)
     val rgb=(0..2).map{px.get(it).toInt() and 255}
     check(abs(rgb[0]-51)<=1 && abs(rgb[1]-102)<=1 && abs(rgb[2]-153)<=1){"pixel $label $rgb"}
     GL30.glDeleteFramebuffers(read); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,source)
     println("pixel/state=$label PASS rgb=$rgb")
 }
 if(args[0]=="before")capture() // Old resize leaves its scratch read FBO bound on the first capture.
 validate("normal")
 // Test actual multisample resolve + resize, not a fake boolean path.
 val ms=GL30.glGenFramebuffers(); val rb=GL30.glGenRenderbuffers()
 GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,rb)
 GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER,4,GL11.GL_RGBA8,2560,1600)
 GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,ms)
 GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_RENDERBUFFER,rb)
 GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
 if(args[0]=="after") validate("MSAA4")
 GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,source)
 Minecraft.getInstance().window.width=1280; Minecraft.getInstance().window.height=800
 if(args[0]=="before")capture()
 validate("resize")
 Minecraft.getInstance().window.width=2560; Minecraft.getInstance().window.height=1600
 if(args[0]=="before")capture()
 validate("restore-size")
 // Asymmetric quadrants catch flipped UVs, wrong source FBOs and scaling errors.
 GL11.glEnable(GL11.GL_SCISSOR_TEST)
 val colors=arrayOf(floatArrayOf(.9f,.1f,.2f),floatArrayOf(.1f,.9f,.2f),floatArrayOf(.1f,.2f,.9f),floatArrayOf(.8f,.8f,.8f))
 for(i in 0..3) {
     GL11.glScissor((i%2)*1280,(i/2)*800,1280,800)
     GL11.glClearColor(colors[i][0],colors[i][1],colors[i][2],1f)
     GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
 }
 GL11.glDisable(GL11.GL_SCISSOR_TEST)
 capture()
 val imageFbo=GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,imageFbo)
 GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,b.softTexture,0)
 for(i in 0..3) {
     val px=BufferUtils.createByteBuffer(4)
     GL11.glReadPixels(160+(i%2)*320,100+(i/2)*200,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,px)
     for(c in 0..2)check(abs((px.get(c).toInt() and 255)-colors[i][c]*255)<=1.1f){"quadrant $i/$c"}
 }
 GL30.glDeleteFramebuffers(imageFbo); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,source)
 println("asymmetric-image/orientation PASS")
 // Match the old raw-copy appearance for sRGB sources, even if the game enables
 // automatic framebuffer conversion. The background textures themselves are RGBA8.
 val srgbTex=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,srgbTex)
 GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL21.GL_SRGB8_ALPHA8,2560,1600,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,0L)
 val srgbFbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,srgbFbo)
 GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,srgbTex,0)
 GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);GL11.glClearColor(.2f,.4f,.6f,1f);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
 val encoded=BufferUtils.createByteBuffer(4);GL11.glReadPixels(2,2,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,encoded)
 val srgbState=state();capture();check(srgbState.contentEquals(state())){"sRGB state restore"}
 // Recreate this temporary read FBO: it was deleted after the orientation check.
 val srgbRead=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,srgbRead)
 GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,b.softTexture,0)
 val actual=BufferUtils.createByteBuffer(4);GL11.glReadPixels(2,2,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,actual)
 for(c in 0..2)check(abs((actual.get(c).toInt() and 255)-(encoded.get(c).toInt() and 255))<=1){"sRGB copy appearance"}
 GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,source)
 GL30.glDeleteFramebuffers(srgbRead);GL30.glDeleteFramebuffers(srgbFbo);GL11.glDeleteTextures(srgbTex)
 println("sRGB-copy-appearance/state PASS")
 // Fixed draws can be interleaved with Minecraft's cached font renderer. Their
 // original bindings must be restored even inside a larger state guard.
 val fixedGuard=GlState();val fixedBefore=state()
 Imm.begin(false);Imm.vertex(-1f,-1f);Imm.vertex(0f,-1f);Imm.vertex(0f,0f);Imm.vertex(-1f,0f);Imm.endFixed()
 check(fixedBefore.contentEquals(state())){"fixed/vanilla interleave bindings"}
 fixedGuard.restore();println("fixed/vanilla-interleave PASS")
 repeat(20){capture()}; GL11.glFinish()
 fun measure(label:String,n:Int,body:()->Unit) {
     val q=GL15.glGenQueries(); GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,q)
     val start=System.nanoTime(); repeat(n){body()}; val submitted=System.nanoTime()
     GL15.glEndQuery(GL33.GL_TIME_ELAPSED); GL11.glFinish()
     val gpu=GL33.glGetQueryObjectui64(q,GL15.GL_QUERY_RESULT)
     println("$label iterations=$n submitMs=${(submitted-start)/1e6/n} gpuMs=${gpu/1e6/n} wallMs=${(System.nanoTime()-start)/1e6/n}")
     GL15.glDeleteQueries(q)
 }
 measure("backdrop2560x1600",120){capture()}
 val shader=ModernGl.shaderManager.get<QuadShader>()
 fun draws() {
     val s=GlState()
     try {
         Gl.bindTexture(b.softTexture)
         repeat(500){
             shader.bind(); shader.setup()
             Imm.begin(); Imm.tex(0f,0f); Imm.vertex(-1f,-1f); Imm.tex(1f,0f); Imm.vertex(-0.99f,-1f)
             Imm.tex(1f,1f); Imm.vertex(-0.99f,-0.99f); Imm.tex(0f,1f); Imm.vertex(-1f,-0.99f); Imm.end()
             shader.unbind()
         }
     } finally { s.restore() }
 }
 val before=state(); draws(); check(before.contentEquals(state())){"draw boundary state"}
 repeat(30){draws()}; GL11.glFinish()
 measure("500-small-draws",200){draws()}
 check(GL11.glGetError()==GL11.GL_NO_ERROR){"GL error after benchmark"}
 // Same-frame capture requests must deduplicate, next-frame requests must run.
 if(args[0]=="after") {
     val counter=b.javaClass.declaredFields.first{it.name=="captures"}.apply{isAccessible=true}
     val initial=counter.getLong(b); b.capture(); b.capture(); check(counter.getLong(b)==initial)
     capture(); check(counter.getLong(b)==initial+1)
     val nested=GlState(); val nested2=GlState(); nested2.restore(); nested.restore(); nested.restore()
     val depth=Imm::class.java.getDeclaredField("scopeDepth").apply{isAccessible=true}; check(depth.getInt(null)==0)
     println("frame-dedup/nested-scope PASS")
 }
 val ownedTextures=mutableListOf<Int>();val ownedFbos=mutableListOf<Int>()
 for(f in b.javaClass.declaredFields) {
     f.isAccessible=true
     if(f.type.name=="me.sorihiko.dango.ui.glass.GlassTarget") {
         val target=f.get(b);ownedTextures+=target.javaClass.getMethod("getTexture").invoke(target) as Int
         ownedFbos+=target.javaClass.getMethod("getFbo").invoke(target) as Int
     } else if(f.name=="captureTexture") ownedTextures+=f.getInt(b)
 }
 b.delete()
 check(ownedTextures.filter{it>0}.all{!GL11.glIsTexture(it)})
 check(ownedFbos.filter{it>0}.all{!GL30.glIsFramebuffer(it)})
 println("owned-texture/framebuffer-release PASS")
 GL30.glDeleteFramebuffers(ms); GL30.glDeleteRenderbuffers(rb)
 GL30.glDeleteFramebuffers(source); GL11.glDeleteTextures(tex)
 println("ALL CHECKS PASS")
 } finally { glfwDestroyWindow(win); glfwTerminate() }
}
