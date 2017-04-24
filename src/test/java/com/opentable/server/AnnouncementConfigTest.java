package com.opentable.server;

import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Response;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import com.opentable.jaxrs.JaxRsClientFactory;
import com.opentable.jaxrs.StandardFeatureGroup;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {
        TestServer.class,
})
@TestPropertySource(properties= {
        "ot.announce.service-type=foo-bar",
})
public class AnnouncementConfigTest {
    @Inject
    private JaxRsClientFactory clientFactory;

    @Test
    public void testConfigAnnounceName() {
        final Client client =
                clientFactory.newClient("test-client", StandardFeatureGroup.PLATFORM_INTERNAL);
        final Response resp = client.target("srvc://foo-bar/").request().get();
        Assert.assertEquals(200, resp.getStatus());
        Assert.assertEquals(TestServer.HELLO_WORLD, resp.getEntity());
    }
}
