package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ServiceInfoCache
import spock.lang.Specification

class ServiceInfoCacheTest extends Specification {

    def "gets what was set"() {
        def f = Fixture.withNginx()
        def target = new ServiceInfoCache(f.project.tasks.composeUp.settings)
        when:
        f.project.tasks.composeUp.up()
        def original = f.project.tasks.composeUp.servicesInfos
        target.set(original, 'state')
        def fromCache = target.get({'state'})
        String networkName = fromCache.find().value.firstContainer.inspection.NetworkSettings.Networks.find().key
        Integer firstPort = fromCache.find().value.firstContainer.tcpPorts.find().key
        then:
        noExceptionThrown()
        original.toString() == fromCache.toString()
        networkName
        firstPort == 80
        cleanup:
        f.project.tasks.composeDown.down()
        f.close()
    }

}
