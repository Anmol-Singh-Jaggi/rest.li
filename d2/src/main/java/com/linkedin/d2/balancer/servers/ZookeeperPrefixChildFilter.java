/*
   Copyright (c) 2020 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.d2.balancer.servers;

import com.linkedin.d2.discovery.stores.zk.ZookeeperEphemeralPrefixGenerator;
import com.linkedin.d2.discovery.stores.zk.ZooKeeperEphemeralStore;
import com.linkedin.d2.discovery.stores.zk.ZookeeperChildFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * ChildPrefixFilter helps to filter the children in {@link ZooKeeperEphemeralStore}
 * to avoid reading other child data when not required. ChildPrefixFilter filter out other child names
 * that are not matching the same prefix generated by the given {@link ZookeeperEphemeralPrefixGenerator}
 * @author Nizar Mankulangara (nmankulangara@linkedin.com)
 */

public class ZookeeperPrefixChildFilter implements ZookeeperChildFilter
{
  private final ZookeeperEphemeralPrefixGenerator _prefixGenerator;

  public ZookeeperPrefixChildFilter(ZookeeperEphemeralPrefixGenerator prefixGenerator)
  {
    _prefixGenerator = prefixGenerator;
  }

  @Override
  public List<String> filter(List<String> children)
  {
    List<String> filteredChildren = new ArrayList<>();
    for (String child : children)
    {
      int separatorIndex = child.lastIndexOf('-');
      if (separatorIndex < 0)
      {
        filteredChildren.add(child);
        continue;
      }

      String childPrefix = child.substring(0, separatorIndex);
      String ephemeralStorePrefix = _prefixGenerator.generatePrefix();
      if (childPrefix.equals(ephemeralStorePrefix))
      {
        filteredChildren.add(child);
      }
      else if (childPrefix.equals(ZooKeeperEphemeralStore.DEFAULT_PREFIX)) // TODO: cleanup this else after everyone migrated to new prefix
      {
        filteredChildren.add(child);
      }
    }

    return filteredChildren;
  }
}
