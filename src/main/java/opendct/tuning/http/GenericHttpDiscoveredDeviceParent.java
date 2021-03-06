/*
 * Copyright 2015-2016 The OpenDCT Authors. All Rights Reserved
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opendct.tuning.http;

import opendct.tuning.discovery.NetworkDiscoveredDeviceParent;

import java.net.Inet4Address;
import java.net.InetAddress;

public class GenericHttpDiscoveredDeviceParent extends NetworkDiscoveredDeviceParent {

    private InetAddress remoteAddress;

    public GenericHttpDiscoveredDeviceParent(String name, int parentId) {
        super(name, parentId, Inet4Address.getLoopbackAddress());

        this.remoteAddress = Inet4Address.getLoopbackAddress();
    }

    @Override
    public InetAddress getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(InetAddress address) {
        remoteAddress = address;
    }

}
