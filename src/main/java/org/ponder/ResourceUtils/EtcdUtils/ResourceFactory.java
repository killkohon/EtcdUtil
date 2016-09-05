/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.ponder.ResourceUtils.EtcdUtils;

import com.sun.xml.internal.bind.v2.runtime.unmarshaller.XsiNilLoader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.responses.EtcdVersionResponse;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.slf4j.LoggerFactory;

/**
 *
 * @author han
 */
public class ResourceFactory implements ManagedServiceFactory {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ResourceFactory.class);
    private static final Map<String, String> PidInstanceMap = new HashMap<>();
    private static final Map<String, ServiceRegistration<EtcdClient>> EtcdClientServiceMap = new HashMap<>();
    private static final Map<String,Dictionary> UnconnectedInstance=new HashMap<>();

    private BundleContext context;

    public BundleContext getContext() {
        return context;
    }

    public void setContext(BundleContext context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "Etcd";
    }

    @Override
    public void updated(String pid, Dictionary dctnr) throws ConfigurationException {
        String InstanceName = null;
        String etcdusername = null;
        String etcdpassword = null;
        List<URI> etcduris = new ArrayList<>();
        Enumeration keys = dctnr.keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            String strkey = key.toString().toLowerCase();
            if (strkey.equalsIgnoreCase("Etcd.Instance")) {
                InstanceName = dctnr.get(key).toString();
            } else if (strkey.equalsIgnoreCase("Etcd.username")) {
                etcdusername = dctnr.get(key).toString();
            } else if (strkey.equalsIgnoreCase("Etcd.password")) {
                etcdpassword = dctnr.get(key).toString();
            } else if (strkey.startsWith("etcd.uri.")) {
                String uri = dctnr.get(key).toString();
                if (uri != null && !uri.isEmpty()) {
                    etcduris.add(URI.create(uri));
                }
            }
        }
        if (InstanceName == null || InstanceName.isEmpty()) {
            log.warn("Etcd的配置缺少Etcd.Instance项，无法发布资源服务");
            return;
        }
        if (etcduris.isEmpty()) {
            log.warn("Etcd的配置缺少Etcd.uri.<id>项(应该至少有一项URI)，无法发布资源服务");
            return;
        }

        String IName = PidInstanceMap.get(pid);

        if (IName != null && !InstanceName.equals(IName)) {
            //pid对应的实例名不一样，不能替换
            log.warn("该Etcd的配置对应的实例已存在，而且实例名[" + InstanceName + "<->" + IName + "]不一致，为避免影响旧有的应用，不做更新，不发布资源服务");
            return;
        }
        EtcdClient etcd = null;
        URI[] etcdurisArray = etcduris.toArray(new URI[etcduris.size()]); 
        
        if (etcdusername != null && !etcdusername.isEmpty()) {
            if (etcdpassword == null || etcdpassword.isEmpty()) {
                etcdpassword = "";
            }
            etcd = new EtcdClient(etcdusername, etcdpassword, etcdurisArray);
        } else {
            etcd = new EtcdClient(etcdurisArray);
        }
        EtcdVersionResponse version=etcd.version();
        if(version==null){
            StringBuilder uristr=new StringBuilder();
            for(URI uri:etcdurisArray){
                if(uristr.length()>0){
                    uristr.append(",");
                }
                uristr.append(uri.toString());
            }
            log.warn("无法连接Etcd:"+uristr.toString());              
            UnconnectedInstance.put(pid, dctnr);
            return;
        }
        Dictionary<String, String> props = new Hashtable<>();
        props.put("instance", InstanceName);
        
        ServiceRegistration registration = context.registerService(EtcdClient.class.getName(), etcd, props);
        if (registration != null) {
            log.info("Register Etcd Resource:"+InstanceName);
            ServiceRegistration oldreg = EtcdClientServiceMap.get(InstanceName);
            if (oldreg != null) {
                EtcdClientServiceMap.remove(InstanceName);
                oldreg.unregister();
            }
            EtcdClientServiceMap.put(InstanceName, registration);
            PidInstanceMap.put(pid, InstanceName);
        }

    }


    /**
     *
     * @param pid
     */
    @Override
    public void deleted(String pid) {
        if (pid != null) {
            String instancename = PidInstanceMap.get(pid);
            if (instancename != null) {
                ServiceRegistration registration = EtcdClientServiceMap.get(instancename);
                if (registration != null) {
                    log.info("Unregister Etcd Resource:"+instancename);
                    registration.unregister();
                }
                EtcdClientServiceMap.remove(instancename);
            }
            PidInstanceMap.remove(pid);
            UnconnectedInstance.remove(pid);
        }
    }

}
