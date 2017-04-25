# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure("2") do |config|
  config.vm.box = "boxcutter/ubuntu1404"

  if Vagrant.has_plugin?("vagrant-cachier")
    config.cache.scope = :box
  end

  config.vm.provider "virtualbox" do |vb|
    vb.memory = "2048"
  end

  config.vm.provider "vmware_fusion" do |v|
    v.vmx["memsize"] = "2048"
    v.vmx["numvcpus"] = "2"
  end

  config.vm.provision "shell", inline: <<-SHELL
    export DEBIAN_FRONTEND=noninteractive
    apt-get -y update
    apt-get -y install apt-transport-https ca-certificates software-properties-common
    apt-key adv --keyserver hkp://p80.pool.sks-keyservers.net:80 --recv-keys 58118E89F3A912897C070ADBF76221572C52609D
    echo "deb https://apt.dockerproject.org/repo ubuntu-trusty main" >> /etc/apt/sources.list.d/docker.list
    add-apt-repository ppa:openjdk-r/ppa
    apt-get -y update
    apt-get -y install docker-engine linux-image-extra-$(uname -r) linux-image-extra-virtual openjdk-8-jdk-headless
    usermod -aG docker vagrant
    # Workaround https://bugs.launchpad.net/ubuntu/+source/ca-certificates-java/+bug/1396760
    /var/lib/dpkg/info/ca-certificates-java.postinst configure
  SHELL
end
