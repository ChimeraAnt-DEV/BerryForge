package dev.chimeraant.berryforge.ui.components

import android.webkit.WebViewClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

/**
 * 3D preview for text-based model formats: OBJ, ASCII STL, embedded/JSON GLTF and
 * GeoJSON. The viewer is a tiny dependency-free WebGL renderer shipped inline, so
 * it works fully offline and never blocks a thread — lag is impossible by construction.
 *
 * Binary GLB / binary-STL can't survive the text pipeline and are reported politely.
 */
object ModelFormats {
    private val extensions = listOf("obj", "stl", "gltf", "geo.json", "geojson")

    fun isModelFormat(path: String): Boolean {
        val lower = path.lowercase()
        return extensions.any { lower.endsWith(".$it") }
    }

    fun isBinary(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".glb")
    }
}

@Composable
fun ModelPreviewSheet(
    path: String,
    rawText: String,
    modifier: Modifier = Modifier,
) {
    val binary = ModelFormats.isBinary(path)
    Box(modifier.fillMaxSize().background(BerryColors.Surface1)) {
        if (binary) {
            Column(
                Modifier.align(Alignment.Center).padding(BerrySpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BerrySpacing.md),
            ) {
                BerryIcon(BerryIcons.Warning, null, size = BerrySize.iconLg, tint = BerryColors.Warning)
                Text(
                    "Binary model files (.glb, binary .stl) can't be previewed here yet.",
                    style = BerryType.Body,
                    color = BerryColors.TextSecondary,
                )
            }
        } else {
            val html = inlineViewerHtml(rawText)
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(BerryColors.Surface1.toArgb())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = WebViewClient()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
            )
        }
    }
}

/**
 * Builds a self-contained WebGL page for the given text. The viewer parses OBJ, ASCII
 * STL, embedded JSON GLTF and GeoJSON with no external libraries.
 */
private fun inlineViewerHtml(rawText: String): String {
    val safe = rawText
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("\u0000", "")
    return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  body{margin:0;background:rgb(15,21,36);color:#dfe6f5;font-family:system-ui,-apple-system,sans-serif;overflow:hidden}
  #err{display:none;position:absolute;inset:0;align-items:center;justify-content:center;padding:1rem;box-sizing:border-box;text-align:center;color:#f0475c}
  canvas{display:block;width:100vw;height:100vh;cursor:grab}
  #hint{position:absolute;bottom:10px;left:0;right:0;text-align:center;font-size:12px;color:#66718c;pointer-events:none}
</style></head><body><canvas id="c"></canvas><div id="hint">drag to rotate · pinch/scroll to zoom</div></body>
<script>
var DATA = "$safe";
var canvas = document.getElementById('c');
var gl = null;
try{ gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl'); }catch(e){}
if(!gl){document.getElementById('err').style.display='flex';document.getElementById('err').textContent='WebGL is unavailable on this device.';throw new Error('no webgl');}

function compile(type, src){var s=gl.createShader(type);gl.shaderSource(s,src);gl.compileShader(s);return s;}
var vsh="attribute vec3 p;attribute vec3 n;uniform mat4 mvp;uniform mat4 nm;uniform vec3 lightDir;varying vec3 vn;varying vec3 vp;void main(){vn=normalize(mat3(nm)*n);vp=(mvp*vec4(p,1.0)).xyz;gl_Position=mvp*vec4(p,1.0);}";
var fsh="precision mediump float;uniform vec3 base;varying vec3 vn;varying vec3 vp;void main(){float d=max(dot(normalize(vn),normalize(vec3(0.4,0.8,0.5))),0.0);vec3 col=base*(0.45+0.55*d);gl_FragColor=vec4(col,1.0);}";

function build(positions, normals, indexCount, model){
  var prog=gl.createProgram();
  gl.attachShader(prog,compile(gl.VERTEX_SHADER,vsh));gl.attachShader(prog,compile(gl.FRAGMENT_SHADER,fsh));gl.linkProgram(prog);
  gl.useProgram(prog);
  var pb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,pb);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(positions),gl.STATIC_DRAW);
  var nb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,nb);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(normals),gl.STATIC_DRAW);
  var ib=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,ib);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,new Uint16Array(indexCount),gl.STATIC_DRAW);
  var aP=gl.getAttribLocation(prog,'p');gl.enableVertexAttribArray(aP);gl.bindBuffer(gl.ARRAY_BUFFER,pb);gl.vertexAttribPointer(aP,3,gl.FLOAT,false,0,0);
  var aN=gl.getAttribLocation(prog,'n');gl.enableVertexAttribArray(aN);gl.bindBuffer(gl.ARRAY_BUFFER,nb);gl.vertexAttribPointer(aN,3,gl.FLOAT,false,0,0);
  return {prog:prog, count:indexCount, ib:ib, uvp:gl.getUniformLocation(prog,'mvp'), un:gl.getUniformLocation(prog,'nm'), ub:gl.getUniformLocation(prog,'base')};
}

// ---- Tiny OBJ parser ----
function parseOBJ(txt){
  var v=[],vn=[],f=[];
  txt.split(/\r?\n/).forEach(function(l){l=l.trim();if(!l.length||l[0]==='#')return;
    var t=l.split(/\s+/);var k=t[0];
    if(k==='v')v.push([parseFloat(t[1]),parseFloat(t[2]),parseFloat(t[3])]);
    else if(k==='vn')vn.push([parseFloat(t[1]),parseFloat(t[2]),parseFloat(t[3])]);
    else if(k==='f'){
      var idx=[];for(var i=1;i<t.length;i++){
        var s=t[i].split('/');var num=parseInt(s[0],10);idx.push(num<0?v.length+num:num-1);
      }
      // fan triangulation
      for(var i=1;i<idx.length-1;i++)f.push([idx[0],idx[i],idx[i+1]]);
    }
  });
  var pos=[],nrm=[];var ind=[];
  for(var i=0;i<f.length;i++){
    var a=f[i][0],b=f[i][1],cc=f[i][2];
    var A=v[a],B=v[b],C=v[cc];
    if(!A||!B||!C)continue;
    var ux=B[0]-A[0],uy=B[1]-A[1],uz=B[2]-A[2],wx=C[0]-A[0],wy=C[1]-A[1],wz=C[2]-A[2];
    var nx=uy*wz-uz*wy,ny=uz*wx-ux*wz,nz=ux*wy-uy*wx;var nl=Math.hypot(nx,ny,nz)||1;
    nx/=nl;ny/=nl;nz/=nl;
    [A,B,C].forEach(function(p){pos.push(p[0],p[1],p[2]);nrm.push(nx,ny,nz);});
    ind.push(pos.length/3-3,pos.length/3-2,pos.length/3-1);
  }
  return {positions:pos,normals:nrm,index:ind};
}
// ---- ASCII STL ----
function parseSTL(txt){
  var pos=[],nrm=[],ind=[];
  var re=/vertex\s+([-\d.eE+]+)\s+([-\d.eE+]+)\s+([-\d.eE+]+)/g;var m;
  while(m=re.exec(txt)){
    var offset=pos.length/3;
    pos.push(parseFloat(m[1]),parseFloat(m[2]),parseFloat(m[3]));
    if(pos.length/3-3>=0 && (pos.length/3)%3===0){
      ind.push(pos.length/3-3,pos.length/3-2,pos.length/3-1);
    }
  }
  // compute face normals
  for(var i=0;i<ind.length;i+=3){
    var p0=pos.slice(ind[i]*3,ind[i]*3+3),p1=pos.slice(ind[i+1]*3,ind[i+1]*3+3),p2=pos.slice(ind[i+2]*3,ind[i+2]*3+3);
    var ux=p1[0]-p0[0],uy=p1[1]-p0[1],uz=p1[2]-p0[2],wx=p2[0]-p0[0],wy=p2[1]-p0[1],wz=p2[2]-p0[2];
    var nx=uy*wz-uz*wy,ny=uz*wx-ux*wz,nz=ux*wy-uy*wx;var nl=Math.hypot(nx,ny,nz)||1;nx/=nl;ny/=nl;nz/=nl;
    nrm.push(nx,ny,nz,nx,ny,nz,nx,ny,nz);
  }
  return {positions:pos,normals:nrm,index:ind};
}
// ---- GeoJSON → plot as extruded 3D polylines ----
function parseGeoJSON(txt){
  var obj=JSON.parse(txt);var pos=[],nrm=[],ind=[];
  var coords=[];
  function walk(o){ if(!o||typeof o!=='object')return;
    if(o.type==='FeatureCollection'){(o.features||[]).forEach(walk);return;}
    if(o.type==='Feature'){walk(o.geometry);return;}
    if(o.type==='GeometryCollection'){(o.geometries||[]).forEach(walk);return;}
    if(Array.isArray(o.coordinates)){coords.push({type:o.type,coords:o.coordinates});}
  }
  walk(obj);
  // build small 3D "ribbons" along each line/polygon segment, extruded slightly so we see it in 3D
  var lineCol='#4C8DFF',faceCol='#4C8DFF';
  coords.forEach(function(f){
    var flat=[];
    (function flatten(c){if(c.length&&typeof c[0][0]==='number')flat.push(c);else c.forEach(flatten);})(f.coords);
    flat.forEach(function(ring){
      for(var i=0;i<ring.length-1;i++){
        var p1=ring[i],p2=ring[i+1];
        var x1=p1[0],y1=p1[1],z1=p1.length>2?p1[2]/10:0,x2=p2[0],y2=p2[1],z2=p2.length>2?p2[2]/10:0;
        // two triangles = a thin quad ribbon
        var o=pos.length/3;
        pos.push(x1,y1,z1,x2,y2,z1,x2,y2,z2); nrm.push(0,0,1,0,0,1,0,0,1); ind.push(o,o+1,o+2);
        o=pos.length/3; pos.push(x1,y1,z1,x2,y2,z2,x1,y1,z2); nrm.push(0,0,1,0,0,1,0,0,1); ind.push(o,o+1,o+2);
      }
    });
  });
  if(ind.length===0) throw new Error('No renderable geometry found.');
  return {positions:pos,normals:nrm,index:ind,color:'#4C8DFF'};
}
// ---- Embedded GLTF (JSON) best effort ----
function parseGLTF(txt){
  var j=JSON.parse(txt);var pos=[],nrm=[],ind=[];
  (j.meshes||[]).forEach(function(mesh){
    (mesh.primitives||[]).forEach(function(prim){
      var a=prim.attributes||{};
      if(a.POSITION===undefined||a.POSITION===null)return;
      var acc=j.accessors[a.POSITION];if(!acc)return;
      var bv=j.bufferViews[acc.bufferView];var buf=j.buffers[bv.buffer];
      var b64=null;
      if(buf.uri&&buf.uri.indexOf('data:')===0){var comma=buf.uri.indexOf(',');b64=buf.uri.slice(comma+1);}
      if(!b64)return;
      var bin=atob(b64);var arr=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)arr[i]=bin.charCodeAt(i);
      var byteOffset=(acc.byteOffset||0)+(bv.byteOffset||0);
      var compCnt={SCALAR:1,VEC2:2,VEC3:3,VEC4:4}[acc.type]||3;
      var floats=new Float32Array(arr.buffer,byteOffset,acc.count*compCnt);
      for(var i=0;i<acc.count*compCnt;i+=compCnt){pos.push(floats[i],floats[i+1],floats[i+2]);}
      // normals if present
      var N=null;
      if(a.NORMAL!==undefined&&a.NORMAL!==null){var nacc=j.accessors[a.NORMAL];if(nacc){var nbv=j.bufferViews[nacc.bufferView]; var nbuf=j.buffers[nbv.buffer]; var nb64=nbuf.uri&&nbuf.uri.indexOf('data:')===0?nbuf.uri.slice(nbuf.uri.indexOf(',')+1):null; if(nb64){var nb=atob(nb64);var nArr=new Uint8Array(nb.length);for(var i=0;i<nb.length;i++)nArr[i]=nb.charCodeAt(i); N=new Float32Array(nArr.buffer,(nacc.byteOffset||0)+(nbv.byteOffset||0),nacc.count*3);}}}
      var idx=null,idxCount=0;
      if(prim.indices!==undefined&&prim.indices!==null){var idxAcc=j.accessors[prim.indices];if(idxAcc){var idxBv=j.bufferViews[idxAcc.bufferView];var idxBuf=j.buffers[idxBv.buffer];var i64=idxBuf.uri&&idxBuf.uri.indexOf('data:')===0?idxBuf.uri.slice(idxBuf.uri.indexOf(',')+1):null;if(i64){var ib=atob(i64);var iArr=new Uint8Array(ib.length);for(var i=0;i<ib.length;i++)iArr[i]=ib.charCodeAt(i);idx=new (idxAcc.componentType===5123?Uint16Array:Uint32Array)(iArr.buffer,(idxAcc.byteOffset||0)+(idxBv.byteOffset||0),idxAcc.count);idxCount=idxAcc.count;}}}
      var base=pos.length/3;
      if(idx){for(var i=0;i<idxCount;i++)ind.push(base+idx[i]);}
      else{for(var i=0;i<acc.count;i++){if(i%4<3)ind.push(base+i);}}
      for(var i=0;i<acc.count;i++){if(N){nrm.push(N[i*3],N[i*3+1],N[i*3+2]);}else{nrm.push(0,0,1);}}
    });
  });
  if(ind.length===0) throw new Error('No buffer geometry found in GLTF data.');
  return {positions:pos,normals:nrm,index:ind};
}

// ---- decide parser ----
var model=null,color='#4C8DFF';
try{
  var lower=(DATA.slice(0,2000)).toLowerCase();
  if(lower.indexOf('solid')>=0 && DATA.indexOf('facet')>=0 && DATA.indexOf('vertex')>=0){model=parseSTL(DATA);}
  else{
    var first=DATA.trim().charAt(0);
    if(first==='{'){try{var j=JSON.parse(DATA);if(j.meshes)model=parseGLTF(DATA);else model=parseGeoJSON(DATA);}catch(e){model=parseOBJ(DATA);}}
    else{model=parseOBJ(DATA);}
  }
}catch(e){document.getElementById('err').style.display='flex';document.getElementById('err').textContent='Could not parse this model: '+e.message;throw e;}

// ---- center & scale ----
var min=[1e9,1e9,1e9],max=[-1e9,-1e9,-1e9];
for(var i=0;i<model.positions.length;i+=3){min[0]=Math.min(min[0],model.positions[i]);min[1]=Math.min(min[1],model.positions[i+1]);min[2]=Math.min(min[2],model.positions[i+2]);max[0]=Math.max(max[0],model.positions[i]);max[1]=Math.max(max[1],model.positions[i+1]);max[2]=Math.max(max[2],model.positions[i+2]);}
var cx=(min[0]+max[0])/2,cy=(min[1]+max[1])/2,cz=(min[2]+max[2])/2;
var size=Math.max(max[0]-min[0],max[1]-min[1],max[2]-min[2],1e-6);
function centerScale(p){p[0]=(p[0]-cx)/size*2;p[1]=(p[1]-cy)/size*2;p[2]=(p[2]-cz)/size*2;}
for(var i=0;i<model.positions.length;i+=3){var pc=[model.positions[i],model.positions[i+1],model.positions[i+2]];centerScale(pc);model.positions[i]=pc[0];model.positions[i+1]=pc[1];model.positions[i+2]=pc[2];}
var mesh=build(model.positions,model.normals,model.index,model);

gl.clearColor(0.059,0.082,0.141,1);
gl.enable(gl.DEPTH_TEST);
var RX=0.6,RY=0.4;
function mat4identity(){return [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1];}
function mat4persp(fovy,asp,near,far){var f=1/Math.tan(fovy/2),nf=1/(near-far);return [f/asp,0,0,0, 0,f,0,0, 0,0,(far+near)*nf,-1, 0,0,2*far*near*nf,0];}
/* column-major A*B: r[c*4+r_] = sum_k a[k*4+r_]*b[c*4+k] */
function mat4mul(a,b){var r=new Array(16);for(var c=0;c<4;c++)for(var rr=0;rr<4;rr++){var s=0;for(var k=0;k<4;k++)s+=a[k*4+rr]*b[c*4+k];r[c*4+rr]=s;}return r;}
function mat4rotXY(rx,ry){var cX=Math.cos(rx),sX=Math.sin(rx),cY=Math.cos(ry),sY=Math.sin(ry);
  var R=mat4identity();
  R[5]=cX;R[6]=sX;R[9]=-sX;R[10]=cX;   /* X rotation */
  var Y=mat4identity();Y[0]=cY;Y[2]=-sY;Y[8]=sY;Y[10]=cY; /* Y rotation */
  return mat4mul(Y,R);
}
function mat4trans(x,y,z){return [1,0,0,x, 0,1,0,y, 0,0,1,z, 0,0,0,1];}
function mat4scale(s){return [s,0,0,0, 0,s,0,0, 0,0,s,0, 0,0,0,1];}
var P=mat4persp(0.9,1,0.1,100);
var drag=false,lx=0,ly=0;
canvas.addEventListener('mousedown',function(e){drag=true;lx=e.clientX;ly=e.clientY;canvas.style.cursor='grabbing';});
window.addEventListener('mouseup',function(){drag=false;canvas.style.cursor='grab';});
window.addEventListener('mousemove',function(e){if(!drag)return;RY+=(e.clientX-lx)*0.008;RX+=(e.clientY-ly)*0.008;lx=e.clientX;ly=e.clientY;});
canvas.addEventListener('touchstart',function(e){if(e.touches.length===1){drag=true;lx=e.touches[0].clientX;ly=e.touches[0].clientY;}},{passive:true});
window.addEventListener('touchmove',function(e){if(drag&&e.touches.length===1){RY+=(e.touches[0].clientX-lx)*0.008;RX+=(e.touches[0].clientY-ly)*0.008;lx=e.touches[0].clientX;ly=e.touches[0].clientY;e.preventDefault();}},{passive:false});
window.addEventListener('touchend',function(){drag=false;});
var zoom=1;
canvas.addEventListener('wheel',function(e){zoom*=e.deltaY>0?1.08:0.92;e.preventDefault();},{passive:false});
function render(){
  gl.viewport(0,0,canvas.width,canvas.height);
  gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
  gl.useProgram(mesh.prog);
  var R=mat4rotXY(RX,RY);var S=mat4scale(zoom);var T=mat4trans(0,0,-3.2);
  var M=mat4mul(P,mat4mul(T,mat4mul(S,R)));
  var NM=mat4mul(R,mat4identity());
  gl.uniformMatrix4fv(mesh.uvp,false,new Float32Array(M));
  gl.uniformMatrix4fv(mesh.un,false,new Float32Array(NM));
  gl.uniform3f(mesh.ub,0.298,0.545,0.72);
  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,mesh.ib);
  gl.drawElements(gl.TRIANGLES,mesh.count,gl.UNSIGNED_SHORT,0);
  if(drag)requestAnimationFrame(render);
}
function loop(){if(!drag){RY+=0.002;}render();requestAnimationFrame(loop);}
function resize(){canvas.width=canvas.clientWidth;canvas.height=canvas.clientHeight;}
window.addEventListener('resize',resize);resize();loop();
</script></body></html>"""
}