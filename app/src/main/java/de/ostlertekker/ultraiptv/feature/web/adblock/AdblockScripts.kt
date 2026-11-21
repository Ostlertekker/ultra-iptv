package de.ostlertekker.ultraiptv.feature.web.adblock

import org.json.JSONArray
import org.json.JSONObject

/** Skripte, die der Browser-Werbeblocker in Seiten einsetzt, und harmlose Ersatzskripte für blockierte Werbe-/Statistikskripte. */
object AdblockScripts {

    /**
     * Anti-Adblock: typische Erkennungsbibliotheken bekommen „kein Werbeblocker“ gemeldet, Werbe-Schnittstellen
     * existieren als harmlose Attrappen. Läuft vor allen Skripten der Seite, nur im Hauptfenster.
     */
    const val ANTI_ADBLOCK = """
(function(){try{
  if(window.__ultraAAB)return;window.__ultraAAB=1;
  var w=window,noop=function(){},self=function(){return this;};
  w.canRunAds=true;w.isAdBlockActive=false;w.adBlockEnabled=false;w.adblockDetected=false;
  var F=function(){};F.prototype={check:function(){return false},emitEvent:self,clearEvent:noop,setOption:self,
    on:function(d,f){if(!d){try{f()}catch(e){}}return this},onDetected:self,onNotDetected:function(f){try{f()}catch(e){}return this}};
  ['FuckAdBlock','BlockAdBlock','SniffAdBlock'].forEach(function(n){if(!w[n]){w[n]=F;}});
  ['fuckAdBlock','blockAdBlock','sniffAdBlock'].forEach(function(n){if(!w[n]){w[n]=new F();}});
  if(!w.adsbygoogle){var a=[];a.loaded=true;a.push=function(){return 0};w.adsbygoogle=a;}
}catch(e){}})();
"""

    /** Setzt die allgemeinen Werbeflächen-Regeln als Stylesheet ein – vor dem Aufbau der Seite (nur Hauptfenster). */
    fun genericCssScript(chunks: List<String>): String {
        val css = JSONObject.quote(chunks.joinToString("\n"))
        return """
(function(){try{
  if(window.top!==window||window.__ultraG)return;window.__ultraG=1;
  var s=document.createElement('style');s.id='__ultra_g';s.textContent=$css;
  (document.head||document.documentElement).appendChild(s);
  // Seiten, die das Stylesheet beim Aufbau entfernen, bekommen es zurück
  new MutationObserver(function(){if(!document.getElementById('__ultra_g')&&!window.__ultraNoG)(document.head||document.documentElement).appendChild(s);})
    .observe(document.documentElement,{childList:true,subtree:false});
}catch(e){}})();
"""
    }

    /**
     * Seitenspezifische Regeln einsetzen. [dropGeneric]: allgemeines Stylesheet entfernen (Seiten-Ausnahme) – bei einzelnen
     * #@#-Ausnahmen wird es ohne diese Selektoren neu aufgebaut ([genericCss], [exceptions]).
     */
    fun cosmeticScript(specificCss: String, dropGeneric: Boolean, genericCss: String, exceptions: List<String>): String {
        val ex = JSONArray(exceptions).toString()
        return """
(function(css,drop,gen,ex){try{
  var d=document,root=d.head||d.documentElement;if(!root)return;
  if(drop){window.__ultraNoG=1;var g=d.getElementById('__ultra_g');if(g)g.remove();
    if(gen&&ex.length){var keep=[];gen.split('\n').forEach(function(rule){var i=rule.lastIndexOf('{');if(i<0)return;
      var sels=rule.substring(0,i).split(',').filter(function(x){return ex.indexOf(x)<0});
      if(sels.length)keep.push(sels.join(',')+rule.substring(i));});css=keep.join('\n')+'\n'+css;}}
  var s=d.getElementById('__ultra_s');if(!s){s=d.createElement('style');s.id='__ultra_s';root.appendChild(s);}
  if(s.textContent!==css)s.textContent=css;
}catch(e){}})(${JSONObject.quote(specificCss)},$dropGeneric,${JSONObject.quote(genericCss)},$ex);
"""
    }

    /** Ersatz für bekannte Werbe-/Statistikskripte (statt leerer Antwort), damit abhängiger Seitencode nicht abbricht. */
    fun surrogate(host: String, path: String): String? = when {
        path.endsWith("/gpt.js") && (host.endsWith("googletagservices.com") || host.endsWith("doubleclick.net") ||
            host.endsWith("googlesyndication.com")) -> GPT
        path.endsWith("/adsbygoogle.js") -> ADSBYGOOGLE
        host.endsWith("google-analytics.com") && (path.endsWith("/analytics.js") || path.endsWith("/ga.js")) -> ANALYTICS
        host.endsWith("googletagmanager.com") && (path.endsWith("/gtm.js") || path.startsWith("/gtag/js")) -> GTM
        else -> null
    }

    private const val GPT = """
(function(){var w=window,noop=function(){},self=function(){return this};
var slot={addService:self,clearCategoryExclusions:self,clearTargeting:self,defineSizeMapping:self,get:function(){return null},
 getAdUnitPath:function(){return''},getAttributeKeys:function(){return[]},getCategoryExclusions:function(){return[]},
 getDomId:function(){return''},getSlotElementId:function(){return''},getSlotId:self,getTargeting:function(){return[]},
 getTargetingKeys:function(){return[]},set:self,setCategoryExclusion:self,setClickUrl:self,setCollapseEmptyDiv:self,
 setTargeting:self,setConfig:self,updateTargetingFromMap:self,getResponseInformation:function(){return null}};
var pubads={addEventListener:self,removeEventListener:self,clear:noop,clearCategoryExclusions:self,clearTagForChildDirectedTreatment:self,
 clearTargeting:self,collapseEmptyDivs:self,defineOutOfPagePassback:function(){return slot},definePassback:function(){return slot},
 disableInitialLoad:noop,display:noop,enableAsyncRendering:noop,enableLazyLoad:noop,enableSingleRequest:noop,enableSyncRendering:noop,
 enableVideoAds:noop,get:function(){return null},getAttributeKeys:function(){return[]},getTargeting:function(){return[]},
 getTargetingKeys:function(){return[]},getSlots:function(){return[]},isInitialLoadDisabled:function(){return true},refresh:noop,
 set:self,setCategoryExclusion:self,setCentering:noop,setCookieOptions:self,setForceSafeFrame:self,setLocation:self,
 setPrivacySettings:self,setPublisherProvidedId:self,setRequestNonPersonalizedAds:self,setSafeFrameConfig:self,
 setTagForChildDirectedTreatment:self,setTargeting:self,setVideoContent:self,updateCorrelator:noop};
var cmd=(w.googletag&&w.googletag.cmd)||[];
var gt=w.googletag={apiReady:true,pubadsReady:true,cmd:{push:function(f){try{f()}catch(e){}return 1}},
 companionAds:function(){return{addEventListener:self,enableSyncLoading:noop,setRefreshUnfilledSlots:noop}},
 content:function(){return{addEventListener:self,setContent:noop}},defineOutOfPageSlot:function(){return slot},
 defineSlot:function(){return slot},destroySlots:noop,disablePublisherConsole:noop,display:noop,enableServices:noop,
 getVersion:function(){return''},pubads:function(){return pubads},setAdIframeTitle:noop,setConfig:noop,sizeMapping:function(){
 return{addSize:self,build:function(){return[]}}},enums:{OutOfPageFormat:{}},secureSignalProviders:{push:noop}};
for(var i=0;i<cmd.length;i++){try{cmd[i]()}catch(e){}}})();
"""

    private const val ADSBYGOOGLE = """
(function(){var w=window,a=w.adsbygoogle||[];a.loaded=true;a.push=function(){return 0};w.adsbygoogle=a;})();
"""

    private const val ANALYTICS = """
(function(){var w=window,noop=function(){};var ga=function(){var a=arguments,l=a.length;
 if(l&&typeof a[l-1]==='object'&&a[l-1]&&typeof a[l-1].hitCallback==='function'){try{a[l-1].hitCallback()}catch(e){}}};
 ga.create=function(){return{get:noop,set:noop,send:noop}};ga.getByName=function(){return null};ga.getAll=function(){return[]};
 ga.remove=noop;ga.loaded=true;var q=w[w.GoogleAnalyticsObject||'ga'];w[w.GoogleAnalyticsObject||'ga']=ga;
 if(q&&q.q){q.q.forEach(function(x){try{ga.apply(null,x)}catch(e){}})}
 w._gaq=w._gaq||{push:noop};w._gat={_getTracker:function(){return{_trackPageview:noop,_trackEvent:noop}}};})();
"""

    private const val GTM = """
(function(){var w=window;w.dataLayer=w.dataLayer||[];var push=w.dataLayer.push;
 w.dataLayer.push=function(o){try{if(o&&typeof o.eventCallback==='function')setTimeout(o.eventCallback,1)}catch(e){}return push.apply(this,arguments)};
 w.gtag=w.gtag||function(){};w.google_tag_manager=w.google_tag_manager||{};})();
"""
}
