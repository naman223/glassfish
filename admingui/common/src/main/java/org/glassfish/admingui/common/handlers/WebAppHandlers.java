/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 * WebAppHandler.java
 *
 * Created on August 10, 2006, 2:32 PM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */
/**
 *
 * @author anilam
 */
package org.glassfish.admingui.common.handlers;

import com.sun.jsftemplating.annotation.HandlerInput;
import com.sun.jsftemplating.annotation.Handler;
import com.sun.jsftemplating.layout.descriptors.handler.HandlerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.glassfish.admingui.common.util.GuiUtil;
import org.glassfish.admingui.common.util.DeployUtil;
import org.glassfish.admingui.common.util.RestResponse;
import org.glassfish.admingui.common.util.RestUtil;



public class WebAppHandlers {

    /** Creates a new instance of ApplicationsHandler */
    public WebAppHandlers() {
    }

   //This is called when user change the default web module of a VS.
   //Need to ensure this VS is in the application-ref virtual server list. If not add it and restart the app for
   //change to take into effect.  refer to issue#8671
   @Handler(id = "EnsureDefaultWebModule",
        input = {
            @HandlerInput(name = "endpoint", type = String.class, required = true),
            @HandlerInput(name = "vsName", type = String.class, required = true),
            @HandlerInput(name = "instanceList", type=List.class, required=true)
        })
    public static void EnsureDefaultWebModule(HandlerContext handlerCtx) throws Exception {
        String endpoint = (String) handlerCtx.getInputValue("endpoint");
        String vsName = (String) handlerCtx.getInputValue("vsName");
        List instanceList = (List) handlerCtx.getInputValue("instanceList");
        Map vsAttrs = RestUtil.getAttributesMap(endpoint+"/" + vsName);
        String webModule= (String) vsAttrs.get("DefaultWebModule");
        if (GuiUtil.isEmpty(webModule))
            return;
        String appName = webModule;
        int index = webModule.indexOf("#");
        if (index != -1){
            appName=webModule.substring(0, index);
        }
        String serverEndPoint = GuiUtil.getSessionValue("REST_URL") + "/servers/server/";
        for (Object serverName : instanceList) {
            String apprefEndpoint = serverEndPoint + serverName + "/application-ref/" + appName;
            Map apprefAttrs = RestUtil.getAttributesMap(apprefEndpoint+"/" + vsName);
            String vsStr = (String) apprefAttrs.get("VirtualServers");
            List vsList = GuiUtil.parseStringList(vsStr, ",");
            if (vsList.contains(vsName)){
                continue;   //the default web module app is already deployed to this vs, no action needed
            }
            //Add to the vs list of this application-ref, then restart the app.
            vsStr=vsStr+","+vsName;
            apprefAttrs.put("VirtualServers", vsStr);
            RestResponse response = RestUtil.sendUpdateRequest(apprefEndpoint, apprefAttrs, null, null, null);
            if (!response.isSuccess()) {
                GuiUtil.getLogger().severe("Update virtual server failed.  parent=" + apprefEndpoint + "; attrsMap =" + apprefAttrs);
                GuiUtil.handleError(handlerCtx, GuiUtil.getMessage("msg.error.checkLog"));
                return;
            }
            List targets = new ArrayList();
            targets.add("domain");
            DeployUtil.reloadApplication(appName, targets , handlerCtx);
        }
   }

   

   //This handler is called after user deleted one more more VS from the VS table.
   //We need to go through all the application-ref to see if the VS specified still exist.  If it doesn't, we need to
   //remove that from the vs list.
   @Handler(id = "checkVsOfAppRef")
   public static void checkVsOfAppRef(HandlerContext handlerCtx) throws Exception{
       String configUrl = GuiUtil.getSessionValue("REST_URL") + "/configs/config/";
       List configs = new ArrayList(RestUtil.getChildMap(configUrl).keySet());
       ArrayList vsList = new ArrayList();
       for (Object cfgName : configs) {
           String vsUrl = configUrl + cfgName + "/http-service/virtual-server";
           List vsNames = new ArrayList(RestUtil.getChildMap(vsUrl).keySet());
           for (Object str : vsNames) {
               if (!vsList.contains(str))
                   vsList.add(str);
           }
       }
       List servers = new ArrayList(RestUtil.getChildMap(GuiUtil.getSessionValue("REST_URL") + "/servers/server").keySet());
       for (Object svrName : servers) {
           String serverEndpoint = GuiUtil.getSessionValue("REST_URL") + "/servers/server/" + svrName;
           List appRefs = new ArrayList(RestUtil.getChildMap(serverEndpoint + "/application-ref").keySet());
           for (Object appRef : appRefs) {
               String apprefEndpoint = serverEndpoint + "/application-ref/" + appRef;
               Map apprefAttrs = RestUtil.getAttributesMap(apprefEndpoint);
               String vsStr = (String) apprefAttrs.get("VirtualServers");
               List<String> lvsList = GuiUtil.parseStringList(vsStr, ",");
               boolean changed = false;
               String newVS = "";
               for(String oneVs: lvsList ){
                   if (! vsList.contains(oneVs)){
                       changed = true;
                       continue;
                   }
                   newVS = newVS+","+oneVs;
               }
               if (changed){
                   newVS = newVS.substring(1);
                   apprefAttrs.put("VirtualServers", vsStr);
                   RestResponse response = RestUtil.sendUpdateRequest(apprefEndpoint, apprefAttrs, null, null, null);
                   if (!response.isSuccess()) {
                       GuiUtil.getLogger().severe("Update virtual server failed.  parent=" + apprefEndpoint + "; attrsMap =" + apprefAttrs);
                       GuiUtil.handleError(handlerCtx, GuiUtil.getMessage("msg.error.checkLog"));
                       return;
                   }
               }
           }
       }
   }

}
