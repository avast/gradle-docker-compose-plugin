# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure("2") do |config|
  config.vm.box = "boxcutter/ubuntu1404"

  if Vagrant.has_plugin?("vagrant-cachier")
    config.cache.scope = :box
  end

  config.vm.provider "virtualbox" do |vb|
    vb.memory = "2048"
    vb.cpus = 2
  end

  config.vm.provider "vmware_fusion" do |v|
    v.vmx["memsize"] = "2048"
    v.vmx["numvcpus"] = "2"
  end

  config.vm.provision "shell", inline: <<-SHELL
    export DEBIAN_FRONTEND=noninteractive
    apt-get -y update
    apt-get -y install apt-transport-https ca-certificates curl software-properties-common
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | apt-key add -
    add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"
    add-apt-repository ppa:openjdk-r/ppa
    apt-get -y update
    apt-get -y install docker-ce linux-image-extra-$(uname -r) linux-image-extra-virtual openjdk-8-jdk-headless
    usermod -aG docker vagrant
    # Workaround https://bugs.launchpad.net/ubuntu/+source/ca-certificates-java/+bug/1396760
    /var/lib/dpkg/info/ca-certificates-java.postinst configure
    curl -sL https://github.com/docker/compose/releases/download/1.12.0/docker-compose-`uname -s`-`uname -m` > /usr/local/bin/docker-compose
    chmod +x /usr/local/bin/docker-compose
  SHELL
end
